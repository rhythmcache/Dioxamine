/*
 * dxls.c - Fast native directory lister for remote ADB devices.
 *
 * Emits NDJSON (newline-delimited JSON), one object per line:
 *   {"type":"meta", ...}    - exactly one, first line
 *   {"type":"entry", ...}   - zero or more
 *   {"type":"warn", ...}    - zero or more (non-fatal per-entry errors)
 *   {"type":"done", ...}    - exactly one, last line (only on success)
 *
 * Usage:
 *   dxls <path>
 *
 * Exit codes:
 *   0  - success (meta.error == null)
 *   1  - could not list the directory (meta.error != null, see meta line)
 *   2  - bad arguments
 *
 *   - Intended to be pushed to
 *     /data/local/tmp on the target device.
 */

 /*
 * TODO:
 * Temporary files may be left behind in the tmp directory. This is a
 * temporary implementation; we plan to replace it with a more robust,
 * atomic approach that guarantees proper cleanup and avoids leaving
 * stale files behind. Until then, this behavior is expected.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include <limits.h>
#include <stdint.h>

/** JSON string escaping */

/** Writes `s` as a JSON string literal (including surrounding quotes) to fp.
 * Handles the standard JSON escapes; anything else is passed through as-is
 * since filenames on Android are UTF-8 and JSON strings are UTF-8 safe. */
static void json_write_escaped(FILE *fp, const char *s) {
    fputc('"', fp);
    for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
        unsigned char c = *p;
        switch (c) {
            case '"':  fputs("\\\"", fp); break;
            case '\\': fputs("\\\\", fp); break;
            case '\n': fputs("\\n", fp);  break;
            case '\r': fputs("\\r", fp);  break;
            case '\t': fputs("\\t", fp);  break;
            default:
                if (c < 0x20) {
                    /* other control characters -> \u00XX */
                    fprintf(fp, "\\u%04x", c);
                } else {
                    fputc(c, fp);
                }
        }
    }
    fputc('"', fp);
}

/** Writes either a JSON string or JSON null, depending on whether s is NULL. */
static void json_write_nullable_string(FILE *fp, const char *s) {
    if (s == NULL) {
        fputs("null", fp);
    } else {
        json_write_escaped(fp, s);
    }
}

/* mode -> octal permission string, e.g. "755" */

static void mode_to_octal_string(mode_t mode, char *out, size_t out_size) {
    /* permission bits only (owner/group/other rwx), matches `chmod` style */
    snprintf(out, out_size, "%03o", (unsigned int)(mode & 0777));
}

/* file type classification */

static const char *ftype_of(mode_t mode) {
    if (S_ISDIR(mode))  return "dir";
    if (S_ISLNK(mode))  return "symlink";
    if (S_ISREG(mode))  return "file";
    return "other"; /* block/char device, fifo, socket, etc. */
}

/* emit a single directory entry line */

static void emit_entry(FILE *out, const char *name, const struct stat *st,
                        const char *symlink_target, int target_is_dir) {
    char mode_str[8];
    mode_to_octal_string(st->st_mode, mode_str, sizeof(mode_str));

    fputs("{\"type\":\"entry\",\"name\":", out);
    json_write_escaped(out, name);

    fputs(",\"ftype\":\"", out);
    fputs(ftype_of(st->st_mode), out);
    fputc('"', out);

    fprintf(out, ",\"size\":%lld", (long long)st->st_size);
    fprintf(out, ",\"mtime\":%lld", (long long)st->st_mtime);
    fputs(",\"mode\":", out);
    json_write_escaped(out, mode_str);
    fprintf(out, ",\"uid\":%u", (unsigned)st->st_uid);
    fprintf(out, ",\"gid\":%u", (unsigned)st->st_gid);

    fputs(",\"symlink_target\":", out);
    json_write_nullable_string(out, symlink_target);
    fprintf(out, ",\"target_is_dir\":%s", target_is_dir ? "true" : "false");

    fputs("}\n", out);
    fflush(out);
}

/* emit a non-fatal per-entry warning line */

static void emit_warn(FILE *out, const char *name, const char *error) {
    fputs("{\"type\":\"warn\",\"name\":", out);
    json_write_escaped(out, name);
    fputs(",\"error\":", out);
    json_write_escaped(out, error);
    fputs("}\n", out);
    fflush(out);
}

/* emit the meta (header) line */

static void emit_meta(FILE *out, const char *path, int readable, int writable,
                       const char *error) {
    fputs("{\"type\":\"meta\",\"path\":", out);
    json_write_escaped(out, path);
    fprintf(out, ",\"readable\":%s", readable ? "true" : "false");
    fprintf(out, ",\"writable\":%s", writable ? "true" : "false");
    fputs(",\"error\":", out);
    json_write_nullable_string(out, error);
    fputs("}\n", out);
    fflush(out);
}

/* emit the done (footer) line */

static void emit_done(FILE *out, uint64_t count) {
    fprintf(out, "{\"type\":\"done\",\"count\":%llu}\n", (unsigned long long)count);
    fflush(out);
}

/* errno -> short "ECODE: message" string  */

static const char *errno_label(int err, char *buf, size_t buf_size) {
    const char *name;
    switch (err) {
        case EACCES: name = "EACCES"; break;
        case ENOENT: name = "ENOENT"; break;
        case ENOTDIR: name = "ENOTDIR"; break;
        case ELOOP:  name = "ELOOP";  break;
        case ENAMETOOLONG: name = "ENAMETOOLONG"; break;
        case EMFILE: name = "EMFILE"; break;
        case ENFILE: name = "ENFILE"; break;
        default:     name = "ERRNO";  break;
    }
    snprintf(buf, buf_size, "%s: %s", name, strerror(err));
    return buf;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s <path>\n", argv[0]);
        return 2;
    }

    const char *path = argv[1];

    /* Normalize trailing slash (except root "/") so joined child paths
     * don't end up with "//" in them. */
    char norm_path[PATH_MAX];
    {
        size_t len = strlen(path);
        if (len == 0) {
            fprintf(stderr, "usage: %s <path>\n", argv[0]);
            return 2;
        }
        if (len >= sizeof(norm_path)) {
            emit_meta(stdout, path, 0, 0, "ENAMETOOLONG: path too long");
            return 1;
        }
        strcpy(norm_path, path);
        while (len > 1 && norm_path[len - 1] == '/') {
            norm_path[len - 1] = '\0';
            len--;
        }
    }

    int readable = (access(norm_path, R_OK) == 0);
    int writable = (access(norm_path, W_OK) == 0);

    DIR *dir = opendir(norm_path);
    if (dir == NULL) {
        char errbuf[128];
        emit_meta(stdout, norm_path, readable, writable,
                  errno_label(errno, errbuf, sizeof(errbuf)));
        return 1;
    }

    /* Directory opened successfully. */
    emit_meta(stdout, norm_path, readable, writable, NULL);

    uint64_t count = 0;
    struct dirent *de;
    errno = 0; /* readdir() only reports errors by leaving errno set and
                  returning NULL; must be cleared once before the loop so
                  a post-loop errno check reflects readdir(), not some
                  earlier unrelated call. */

    while ((de = readdir(dir)) != NULL) {
        errno = 0; /* clear immediately: lstat()/readlink() below may set
                      errno on failure and take a `continue` path that
                      doesn't run emit_entry(); without this reset here,
                      that stale errno would incorrectly look like a
                      readdir() failure once the loop exits at EOF. */
        const char *name = de->d_name;

        /* skip "." and ".." - caller (Kotlin side) synthesizes its own
         * ".." parent-nav entry, matching existing app behavior */
        if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) {
            continue;
        }

        char child_path[PATH_MAX];
        int written;
        if (strcmp(norm_path, "/") == 0) {
            written = snprintf(child_path, sizeof(child_path), "/%s", name);
        } else {
            written = snprintf(child_path, sizeof(child_path), "%s/%s", norm_path, name);
        }
        if (written < 0 || (size_t)written >= sizeof(child_path)) {
            emit_warn(stdout, name, "ENAMETOOLONG: joined path too long");
            continue;
        }

        struct stat st;
        if (lstat(child_path, &st) != 0) {
            char errbuf[128];
            emit_warn(stdout, name, errno_label(errno, errbuf, sizeof(errbuf)));
            continue;
        }

        char link_target[PATH_MAX];
        const char *symlink_target = NULL;
        if (S_ISLNK(st.st_mode)) {
            ssize_t n = readlink(child_path, link_target, sizeof(link_target) - 1);
            if (n >= 0) {
                link_target[n] = '\0';
                symlink_target = link_target;
            }
            /* if readlink fails (e.g. race, permission), just leave target
             * as null - entry is still reported as a symlink */
        }

        int target_is_dir = 0;
        if (S_ISDIR(st.st_mode)) {
            target_is_dir = 1;
        } else if (S_ISLNK(st.st_mode)) {
            struct stat target_st;
            if (stat(child_path, &target_st) == 0) {
                if (S_ISDIR(target_st.st_mode)) {
                    target_is_dir = 1;
                }
            }
        }

        emit_entry(stdout, name, &st, symlink_target, target_is_dir);
        count++;
    }

    /* Distinguish "readdir returned NULL because we're done" from
     * "readdir returned NULL because of an error partway through". */
    if (errno != 0) {
        char errbuf[128];
        emit_warn(stdout, "", errno_label(errno, errbuf, sizeof(errbuf)));
    }

    closedir(dir);
    emit_done(stdout, count);
    return 0;
}