/* Unpacks RAR (including the newer RAR5), and other archives, with libarchive. */
#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <archive.h>
#include <archive_entry.h>

static jstring fail(JNIEnv *env, struct archive *a, struct archive *w, const char *what) {
    char buf[600];
    snprintf(buf, sizeof buf, "%s: %s", what, a && archive_error_string(a) ? archive_error_string(a) : (w && archive_error_string(w) ? archive_error_string(w) : "unknown error"));
    if (a) archive_read_free(a);
    if (w) archive_write_free(w);
    return (*env)->NewStringUTF(env, buf);
}

/* Returns null when it worked, or a message when it did not. */
JNIEXPORT jstring JNICALL Java_com_mobileaistudio_app_Arc_extract(JNIEnv *env, jclass cls, jstring jsrc, jstring jdest, jstring jpass) {
    const char *src = (*env)->GetStringUTFChars(env, jsrc, 0);
    const char *dest = (*env)->GetStringUTFChars(env, jdest, 0);
    const char *pass = (*env)->GetStringUTFChars(env, jpass, 0);
    jstring result = NULL;
    struct archive *a = archive_read_new();
    struct archive *w = archive_write_disk_new();
    archive_read_support_format_all(a);
    archive_read_support_filter_all(a);
    if (pass && pass[0]) archive_read_add_passphrase(a, pass);
    archive_write_disk_set_options(w, ARCHIVE_EXTRACT_TIME | ARCHIVE_EXTRACT_SECURE_NODOTDOT | ARCHIVE_EXTRACT_SECURE_SYMLINKS | ARCHIVE_EXTRACT_SECURE_NOABSOLUTEPATHS);
    if (archive_read_open_filename(a, src, 65536) != ARCHIVE_OK) { result = fail(env, a, w, "Could not open the archive"); goto done; }
    struct archive_entry *e;
    int r;
    while ((r = archive_read_next_header(a, &e)) == ARCHIVE_OK || r == ARCHIVE_WARN) {
        const char *name = archive_entry_pathname(e);
        if (!name) continue;
        size_t n = strlen(dest) + strlen(name) + 2;
        char *full = malloc(n);
        snprintf(full, n, "%s/%s", dest, name);
        archive_entry_set_pathname(e, full);
        free(full);
        archive_entry_set_uid(e, 0); archive_entry_set_gid(e, 0);
        archive_entry_set_perm(e, archive_entry_filetype(e) == AE_IFDIR ? 0777 : 0666);
        if (archive_write_header(w, e) == ARCHIVE_OK) {
            const void *buf; size_t size; la_int64_t off;
            int rr;
            while ((rr = archive_read_data_block(a, &buf, &size, &off)) == ARCHIVE_OK) {
                if (archive_write_data_block(w, buf, size, off) != ARCHIVE_OK) break;
            }
            if (rr != ARCHIVE_EOF && rr != ARCHIVE_OK) { result = fail(env, a, w, "Could not read a file inside (wrong password?)"); goto done2; }
        }
        archive_write_finish_entry(w);
    }
    if (r != ARCHIVE_EOF) { result = fail(env, a, w, "Could not read the archive (wrong password?)"); goto done2; }
    archive_read_free(a); archive_write_free(w);
    goto done;
done2:
    /* fail() already freed both */
done:
    (*env)->ReleaseStringUTFChars(env, jsrc, src);
    (*env)->ReleaseStringUTFChars(env, jdest, dest);
    (*env)->ReleaseStringUTFChars(env, jpass, pass);
    return result;
}
