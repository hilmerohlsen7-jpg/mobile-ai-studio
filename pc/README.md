# Phone Transfer (Windows)

Move files between a Windows PC and the phone, over Wi-Fi.

1. Phone: open File Manager, go to Network, start the WebDAV server. Turn "read only" off if you also want to send files *to* the phone. Note the address and the PIN it shows.
2. PC: download PhoneTransfer.exe from the Releases page ("Phone Transfer for Windows") and run it.
3. Type the phone address (for example 192.168.1.5:8090) and the PIN, press Connect.
4. Pick files or folders and press "Download selected to PC", or send files and folders from the PC to the phone.

The phone never lets this program delete, rename or replace anything. If a name already exists, the new file gets "(1)" added.

Windows may warn that the program is from an unknown publisher (it is not code-signed). Choose "More info", then "Run anyway".

## PC storage (keep files on the PC, free space on the phone)

1. PC: open the "PC storage" tab, choose the folder where the files should live, set a password and press "Start storage server". Keep the window open. Allow the firewall question for Private networks.
2. Phone: File Manager > FTP > add a server with the address, port, user `phone` and the password shown.
3. Phone: add a sync task (File Manager > Sync) from the phone folder to that server with the mode "Move". Run it; the files are copied to the PC and the originals go to the phone's Trash (empty the Trash to really free the space).
4. When the phone is on your home Wi-Fi and the program is running, open the FTP server in File Manager to browse and open the files.
