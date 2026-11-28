"""Phone Transfer: move files between this Windows PC and the phone running Mobile AI Studio.

On the phone: File Manager > Network > WebDAV server > start it (turn off "read only" to also send files to the phone).
Here: type the address shown on the phone and the PIN, then press Connect.
The phone never lets this program delete, rename or replace files on it.
"""
import base64
import json
import os
import queue
import threading
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime

NS = {"D": "DAV:"}
CHUNK = 1 << 20


class Dav:
    def __init__(self, base, pin):
        base = base.strip()
        if not base.startswith("http"):
            base = "http://" + base
        self.base = base.rstrip("/")
        self.auth = "Basic " + base64.b64encode(("phone:" + pin).encode()).decode()

    def url(self, path):
        return self.base + urllib.parse.quote(path, safe="/")

    def open(self, method, path, data=None, headers=None, timeout=30):
        h = {"Authorization": self.auth}
        h.update(headers or {})
        req = urllib.request.Request(self.url(path), data=data, method=method, headers=h)
        return urllib.request.urlopen(req, timeout=timeout)

    def list(self, path):
        with self.open("PROPFIND", path, headers={"Depth": "1", "Content-Type": "text/xml"}, data=b"") as r:
            tree = ET.fromstring(r.read())
        here = urllib.parse.unquote(urllib.parse.urlparse(self.url(path)).path).rstrip("/")
        items = []
        for resp in tree.findall("D:response", NS):
            href = urllib.parse.unquote(urllib.parse.urlparse(resp.findtext("D:href", "", NS)).path)
            if href.rstrip("/") == here:
                continue
            is_dir = resp.find(".//D:resourcetype/D:collection", NS) is not None
            size = int(resp.findtext(".//D:getcontentlength", "0", NS) or 0)
            mod = resp.findtext(".//D:getlastmodified", "", NS)
            try:
                mod = parsedate_to_datetime(mod).astimezone().strftime("%Y-%m-%d %H:%M")
            except Exception:
                mod = ""
            items.append({"name": href.rstrip("/").split("/")[-1], "dir": is_dir, "size": size, "mod": mod})
        items.sort(key=lambda i: (not i["dir"], i["name"].lower()))
        return items

    def mkdir(self, path):
        try:
            self.open("MKCOL", path).close()
        except urllib.error.HTTPError as e:
            if e.code != 405:  # 405 = already there
                raise

    def download(self, path, dest, progress):
        with self.open("GET", path, timeout=60) as r, open(dest, "wb") as f:
            while True:
                b = r.read(CHUNK)
                if not b:
                    break
                f.write(b)
                progress(len(b))

    def exists(self, path):
        try:
            self.open("PROPFIND", path, headers={"Depth": "0"}, data=b"").close()
            return True
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            raise

    def free_name(self, folder, name):
        stem, ext = os.path.splitext(name)
        cand, i = name, 1
        while self.exists(folder.rstrip("/") + "/" + cand):
            cand = "%s (%d)%s" % (stem, i, ext)
            i += 1
        return cand

    def upload(self, local, folder, progress):
        name = self.free_name(folder, os.path.basename(local))
        size = os.path.getsize(local)

        class Body:
            def __init__(s):
                s.f = open(local, "rb")

            def read(s, n=-1):
                b = s.f.read(CHUNK if n < 0 else n)
                if b:
                    progress(len(b))
                return b

        b = Body()
        req = urllib.request.Request(self.url(folder.rstrip("/") + "/" + name), data=b, method="PUT",
                                     headers={"Authorization": self.auth, "Content-Length": str(size)})
        try:
            urllib.request.urlopen(req, timeout=120).close()
        finally:
            b.f.close()
        return name


def human(n):
    for u in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or u == "TB":
            return ("%d %s" % (n, u)) if u == "B" else ("%.1f %s" % (n, u))
        n /= 1024.0


def explain(e):
    if isinstance(e, urllib.error.HTTPError):
        return {401: "Wrong PIN.", 403: "The phone refused this (read-only share, or the name already exists).",
                404: "Not found on the phone.", 429: "Too many wrong tries. Wait a minute."}.get(e.code, "The phone answered: %s" % e)
    if isinstance(e, urllib.error.URLError):
        return "Cannot reach the phone. Check the address, that the WebDAV server is running, and that both are on the same Wi-Fi."
    return str(e)


def run_gui():
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    cfg_path = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "PhoneTransfer.json")
    try:
        cfg = json.load(open(cfg_path))
    except Exception:
        cfg = {}

    root = tk.Tk()
    root.title("Phone Transfer")
    root.geometry("760x560")
    state = {"dav": None, "path": "/", "items": []}
    jobs = queue.Queue()

    nb = ttk.Notebook(root)
    nb.pack(fill="both", expand=True)
    tab1 = ttk.Frame(nb)
    tab2 = ttk.Frame(nb)
    nb.add(tab1, text="Transfer files")
    nb.add(tab2, text="PC storage (keep files here to free the phone)")
    top = ttk.Frame(tab1, padding=8)
    top.pack(fill="x")
    ttk.Label(top, text="Phone address").grid(row=0, column=0, sticky="w")
    addr = ttk.Entry(top, width=28)
    addr.insert(0, cfg.get("addr", "192.168.1.2:8090"))
    addr.grid(row=0, column=1, padx=6)
    ttk.Label(top, text="PIN").grid(row=0, column=2)
    pin = ttk.Entry(top, width=10, show="*")
    pin.insert(0, cfg.get("pin", ""))
    pin.grid(row=0, column=3, padx=6)
    connect = ttk.Button(top, text="Connect")
    connect.grid(row=0, column=4, padx=6)

    bar = ttk.Frame(tab1, padding=(8, 0))
    bar.pack(fill="x")
    up_btn = ttk.Button(bar, text="Up")
    up_btn.pack(side="left")
    path_var = tk.StringVar(value="Not connected")
    ttk.Label(bar, textvariable=path_var).pack(side="left", padx=8)

    cols = ("size", "mod")
    tree = ttk.Treeview(tab1, columns=cols, selectmode="extended")
    tree.heading("#0", text="Name")
    tree.heading("size", text="Size")
    tree.heading("mod", text="Changed")
    tree.column("#0", width=420)
    tree.column("size", width=90, anchor="e")
    tree.column("mod", width=140)
    tree.pack(fill="both", expand=True, padx=8, pady=6)

    btns = ttk.Frame(tab1, padding=(8, 0))
    btns.pack(fill="x")
    b_dl = ttk.Button(btns, text="Download selected to PC")
    b_up = ttk.Button(btns, text="Send files to phone")
    b_upd = ttk.Button(btns, text="Send folder to phone")
    b_new = ttk.Button(btns, text="New folder")
    b_ref = ttk.Button(btns, text="Refresh")
    for b in (b_dl, b_up, b_upd, b_new, b_ref):
        b.pack(side="left", padx=3)

    prog = ttk.Progressbar(tab1, mode="determinate")
    prog.pack(fill="x", padx=8, pady=(8, 0))
    status = tk.StringVar(value="Start the WebDAV server on the phone, then connect.")
    ttk.Label(tab1, textvariable=status, padding=8, wraplength=720).pack(fill="x")

    def say(t):
        status.set(t)

    def ui(fn, *a):
        root.after(0, fn, *a)

    def save_cfg():
        try:
            json.dump({"addr": addr.get(), "pin": pin.get()}, open(cfg_path, "w"))
        except Exception:
            pass

    def refresh():
        d = state["dav"]
        if not d:
            return

        def work():
            try:
                items = d.list(state["path"])
                ui(show, items)
            except Exception as e:
                ui(say, explain(e))
        threading.Thread(target=work, daemon=True).start()

    def show(items):
        state["items"] = items
        tree.delete(*tree.get_children())
        for i in items:
            tree.insert("", "end", iid=i["name"], text=("[folder]  " if i["dir"] else "") + i["name"],
                        values=("" if i["dir"] else human(i["size"]), i["mod"]))
        path_var.set("Phone: " + state["path"])
        say("%d items." % len(items))

    def do_connect():
        d = Dav(addr.get(), pin.get())
        state["dav"], state["path"] = d, "/"
        save_cfg()
        say("Connecting...")
        refresh()

    def go_up():
        if state["path"] != "/":
            state["path"] = "/".join(state["path"].rstrip("/").split("/")[:-1]) or "/"
            refresh()

    def on_open(_e):
        sel = tree.selection()
        if len(sel) != 1:
            return
        it = next((i for i in state["items"] if i["name"] == sel[0]), None)
        if it and it["dir"]:
            state["path"] = state["path"].rstrip("/") + "/" + it["name"]
            refresh()

    class Meter:
        def __init__(self, total):
            self.total, self.done = max(total, 1), 0
            ui(prog.configure, {"value": 0, "maximum": self.total})

        def __call__(self, n):
            self.done += n
            ui(prog.configure, {"value": self.done})

    def walk_remote(d, path, rel=""):
        for it in d.list(path):
            p = path.rstrip("/") + "/" + it["name"]
            r = rel + it["name"]
            if it["dir"]:
                yield ("dir", p, r + "/", 0)
                yield from walk_remote(d, p, r + "/")
            else:
                yield ("file", p, r, it["size"])

    def download():
        d = state["dav"]
        names = tree.selection()
        if not d or not names:
            say("Select something on the phone first.")
            return
        dest = filedialog.askdirectory(title="Save into which folder on this PC?")
        if not dest:
            return

        def work():
            try:
                todo = []
                for n in names:
                    it = next(i for i in state["items"] if i["name"] == n)
                    p = state["path"].rstrip("/") + "/" + n
                    if it["dir"]:
                        todo.append(("dir", p, n + "/", 0))
                        todo += list(walk_remote(d, p, n + "/"))
                    else:
                        todo.append(("file", p, n, it["size"]))
                meter = Meter(sum(t[3] for t in todo))
                count = 0
                for kind, p, rel, size in todo:
                    target = os.path.join(dest, *rel.rstrip("/").split("/"))
                    if kind == "dir":
                        os.makedirs(target, exist_ok=True)
                        continue
                    os.makedirs(os.path.dirname(target), exist_ok=True)
                    if os.path.exists(target):
                        stem, ext = os.path.splitext(target)
                        k = 1
                        while os.path.exists("%s (%d)%s" % (stem, k, ext)):
                            k += 1
                        target = "%s (%d)%s" % (stem, k, ext)
                    ui(say, "Downloading %s" % rel)
                    d.download(p, target, meter)
                    count += 1
                ui(say, "Done. %d files saved in %s" % (count, dest))
            except Exception as e:
                ui(say, explain(e))
        threading.Thread(target=work, daemon=True).start()

    def send(paths_files):
        d = state["dav"]
        if not d:
            say("Connect first.")
            return

        def work():
            try:
                total = sum(os.path.getsize(l) for l, _ in paths_files)
                meter = Meter(total)
                made = set()
                for local, rel in paths_files:
                    folder = state["path"].rstrip("/")
                    parts = rel.split("/")[:-1]
                    for k in range(len(parts)):
                        sub = folder + "/" + "/".join(parts[:k + 1])
                        if sub not in made:
                            d.mkdir(sub)
                            made.add(sub)
                    target = folder + ("/" + "/".join(parts) if parts else "")
                    ui(say, "Sending %s" % rel)
                    d.upload(local, target, meter)
                ui(say, "Done. %d files sent." % len(paths_files))
                ui(refresh)
            except Exception as e:
                ui(say, explain(e))
        threading.Thread(target=work, daemon=True).start()

    def send_files():
        fs = filedialog.askopenfilenames(title="Pick files to send to the phone")
        if fs:
            send([(f, os.path.basename(f)) for f in fs])

    def send_folder():
        d = filedialog.askdirectory(title="Pick a folder to send to the phone")
        if not d:
            return
        out = []
        base = os.path.dirname(d)
        for r, _ds, fs in os.walk(d):
            for f in fs:
                p = os.path.join(r, f)
                out.append((p, os.path.relpath(p, base).replace(os.sep, "/")))
        if out:
            send(out)
        else:
            say("That folder has no files.")

    def new_folder():
        d = state["dav"]
        if not d:
            return
        win = tk.Toplevel(root)
        win.title("New folder")
        e = ttk.Entry(win, width=30)
        e.pack(padx=10, pady=10)
        e.focus()

        def ok():
            n = e.get().strip()
            win.destroy()
            if not n:
                return

            def work():
                try:
                    d.mkdir(state["path"].rstrip("/") + "/" + n)
                    ui(refresh)
                except Exception as ex:
                    ui(say, explain(ex))
            threading.Thread(target=work, daemon=True).start()
        ttk.Button(win, text="Create", command=ok).pack(pady=(0, 10))
        win.bind("<Return>", lambda _e: ok())


    # ---- PC storage tab: the PC keeps the files, the phone reaches them over home Wi-Fi ----
    srv = {"server": None}
    folder_var = tk.StringVar(value=cfg.get("store", os.path.join(os.path.expanduser("~"), "PhoneStorage")))
    pin_var = tk.StringVar(value=cfg.get("spin", str(100000 + int.from_bytes(os.urandom(3), "big") % 900000)))
    port_var = tk.StringVar(value=cfg.get("sport", "2121"))
    info = tk.StringVar(value="Stopped.")
    f2 = ttk.Frame(tab2, padding=12)
    f2.pack(fill="both", expand=True)
    ttk.Label(f2, wraplength=700, justify="left", text="This turns a folder on this PC into storage for the phone. On the phone, add it as an FTP server "
              "(File Manager > FTP) and use a sync task with the mode Move to send files here and free phone space. "
              "Whenever the phone is on the same Wi-Fi you can browse and open them from the phone's File Manager.").pack(anchor="w")
    row = ttk.Frame(f2)
    row.pack(fill="x", pady=8)
    ttk.Label(row, text="Folder on this PC").grid(row=0, column=0, sticky="w")
    ttk.Entry(row, textvariable=folder_var, width=50).grid(row=0, column=1, padx=6)
    ttk.Button(row, text="Choose...", command=lambda: folder_var.set(filedialog.askdirectory() or folder_var.get())).grid(row=0, column=2)
    ttk.Label(row, text="Password").grid(row=1, column=0, sticky="w", pady=4)
    ttk.Entry(row, textvariable=pin_var, width=20).grid(row=1, column=1, sticky="w", padx=6)
    ttk.Label(row, text="Port").grid(row=2, column=0, sticky="w")
    ttk.Entry(row, textvariable=port_var, width=8).grid(row=2, column=1, sticky="w", padx=6)
    toggle = ttk.Button(f2, text="Start storage server")
    toggle.pack(anchor="w", pady=6)
    ttk.Label(f2, textvariable=info, wraplength=700, justify="left").pack(anchor="w", pady=6)

    def lan_ip():
        import socket
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
        except Exception:
            return "this-PC-address"
        finally:
            s.close()

    def toggle_server():
        if srv["server"]:
            try:
                srv["server"].close_all()
            except Exception:
                pass
            srv["server"] = None
            toggle.config(text="Start storage server")
            info.set("Stopped.")
            return
        try:
            from pyftpdlib.authorizers import DummyAuthorizer
            from pyftpdlib.handlers import FTPHandler
            from pyftpdlib.servers import ThreadedFTPServer
            folder = folder_var.get().strip()
            os.makedirs(folder, exist_ok=True)
            if not pin_var.get().strip():
                raise ValueError("Type a password first.")
            auth = DummyAuthorizer()
            auth.add_user("phone", pin_var.get().strip(), folder, perm="elradfmwMT")

            class Handler(FTPHandler):
                pass
            Handler.authorizer = auth
            Handler.passive_ports = range(60000, 60100)
            Handler.banner = "Phone storage ready."
            server = ThreadedFTPServer(("0.0.0.0", int(port_var.get())), Handler)
            threading.Thread(target=lambda: server.serve_forever(handle_exit=False), daemon=True).start()
            srv["server"] = server
            cfg.update({"store": folder, "spin": pin_var.get().strip(), "sport": port_var.get()})
            try:
                json.dump(cfg, open(cfg_path, "w"))
            except Exception:
                pass
            toggle.config(text="Stop storage server")
            info.set("Running. On the phone add an FTP server with:\n  Address: %s\n  Port: %s\n  User: phone\n  Password: %s\n"
                     "Keep this window open (you can minimise it). If Windows asks about the firewall, allow it on Private networks."
                     % (lan_ip(), port_var.get(), pin_var.get().strip()))
        except Exception as e:
            info.set("Could not start: %s" % e)
    toggle.config(command=toggle_server)

    connect.config(command=do_connect)
    up_btn.config(command=go_up)
    b_dl.config(command=download)
    b_up.config(command=send_files)
    b_upd.config(command=send_folder)
    b_new.config(command=new_folder)
    b_ref.config(command=refresh)
    tree.bind("<Double-1>", on_open)
    root.mainloop()


if __name__ == "__main__":
    run_gui()
