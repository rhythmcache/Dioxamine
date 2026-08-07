/*
 * dxls.c - Fast native directory lister for remote ADB devices, served
 * over a persistent abstract Unix domain socket (see dxsock.h).
 *
 * On start:
 *   - unlinks its own executable path immediately (self-delete while
 *     running; inode stays alive until process exit)
 *   - binds abstract socket "dxls"
 *   - accepts connections in a loop, with an idle timeout: if no new
 *     connection arrives within DXLS_IDLE_TIMEOUT_SECS of the last one
 *     finishing, the process exits on its own.
 *
 * Per connection, client sends ONE line:
 *   {"cmd":"list","path":"/sdcard/DCIM"}
 *
 * Server streams back the same NDJSON lines as before:
 *   {"type":"meta", ...}    - exactly one, first line
 *   {"type":"entry", ...}   - zero or more
 *   {"type":"warn", ...}    - zero or more (non-fatal per-entry errors)
 *   {"type":"done", ...}    - exactly one, last line (only on success)
 *
 * Then the server closes its end; client should close too. Server keeps
 * running for the next connection.
 *
 * Exit conditions (see serve_with_idle_timeout / main for exact detail):
 *   - idle timeout with no connections -> exit(0)
 *   - dxsock_listen() setup failure -> exit(1)
 *   - fatal signal (e.g. process killed, adb shell session torn down) ->
 *     killed by signal, not a clean exit; no atexit hook runs, but that's
 *     fine since we already self-unlinked at start.
 */

#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define DXSOCK_IMPLEMENTATION
#include "dxsock.h"

#define DXLS_SOCKET_NAME "dxls"
#define DXLS_IDLE_TIMEOUT_SECS 60

static void json_write_escaped(FILE *fp, const char *s) {
  fputc('"', fp);
  for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
    unsigned char c = *p;
    switch (c) {
      case '"':
        fputs("\\\"", fp);
        break;
      case '\\':
        fputs("\\\\", fp);
        break;
      case '\n':
        fputs("\\n", fp);
        break;
      case '\r':
        fputs("\\r", fp);
        break;
      case '\t':
        fputs("\\t", fp);
        break;
      default:
        if (c < 0x20) {
          fprintf(fp, "\\u%04x", c);
        } else {
          fputc(c, fp);
        }
    }
  }
  fputc('"', fp);
}

static void json_write_nullable_string(FILE *fp, const char *s) {
  if (s == NULL) {
    fputs("null", fp);
  } else {
    json_write_escaped(fp, s);
  }
}

static void mode_to_octal_string(mode_t mode, char *out, size_t out_size) {
  snprintf(out, out_size, "%03o", (unsigned int)(mode & 0777));
}

static const char *ftype_of(mode_t mode) {
  if (S_ISDIR(mode)) return "dir";
  if (S_ISLNK(mode)) return "symlink";
  if (S_ISREG(mode)) return "file";
  return "other";
}

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

static void emit_warn(FILE *out, const char *name, const char *error) {
  fputs("{\"type\":\"warn\",\"name\":", out);
  json_write_escaped(out, name);
  fputs(",\"error\":", out);
  json_write_escaped(out, error);
  fputs("}\n", out);
  fflush(out);
}

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

static void emit_done(FILE *out, uint64_t count) {
  fprintf(out, "{\"type\":\"done\",\"count\":%llu}\n",
          (unsigned long long)count);
  fflush(out);
}

static const char *errno_label(int err, char *buf, size_t buf_size) {
  const char *name;
  switch (err) {
    case EACCES:
      name = "EACCES";
      break;
    case ENOENT:
      name = "ENOENT";
      break;
    case ENOTDIR:
      name = "ENOTDIR";
      break;
    case ELOOP:
      name = "ELOOP";
      break;
    case ENAMETOOLONG:
      name = "ENAMETOOLONG";
      break;
    case EMFILE:
      name = "EMFILE";
      break;
    case ENFILE:
      name = "ENFILE";
      break;
    default:
      name = "ERRNO";
      break;
  }
  snprintf(buf, buf_size, "%s: %s", name, strerror(err));
  return buf;
}

static void list_directory(const char *path, FILE *out) {
  char norm_path[PATH_MAX];
  size_t len = strlen(path);

  if (len == 0) {
    emit_meta(out, path, 0, 0, "EINVAL: empty path");
    return;
  }
  if (len >= sizeof(norm_path)) {
    emit_meta(out, path, 0, 0, "ENAMETOOLONG: path too long");
    return;
  }
  strcpy(norm_path, path);
  while (len > 1 && norm_path[len - 1] == '/') {
    norm_path[len - 1] = '\0';
    len--;
  }

  int readable = (access(norm_path, R_OK) == 0);
  int writable = (access(norm_path, W_OK) == 0);

  DIR *dir = opendir(norm_path);
  if (dir == NULL) {
    char errbuf[128];
    emit_meta(out, norm_path, readable, writable,
              errno_label(errno, errbuf, sizeof(errbuf)));
    return;
  }

  emit_meta(out, norm_path, readable, writable, NULL);

  uint64_t count = 0;
  struct dirent *de;
  errno = 0;

  while ((de = readdir(dir)) != NULL) {
    errno = 0;
    const char *name = de->d_name;

    if (strcmp(name, ".") == 0 || strcmp(name, "..") == 0) {
      continue;
    }

    char child_path[PATH_MAX];
    int written;
    if (strcmp(norm_path, "/") == 0) {
      written = snprintf(child_path, sizeof(child_path), "/%s", name);
    } else {
      written =
          snprintf(child_path, sizeof(child_path), "%s/%s", norm_path, name);
    }
    if (written < 0 || (size_t)written >= sizeof(child_path)) {
      emit_warn(out, name, "ENAMETOOLONG: joined path too long");
      continue;
    }

    struct stat st;
    if (lstat(child_path, &st) != 0) {
      char errbuf[128];
      emit_warn(out, name, errno_label(errno, errbuf, sizeof(errbuf)));
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

    emit_entry(out, name, &st, symlink_target, target_is_dir);
    count++;
  }

  if (errno != 0) {
    char errbuf[128];
    emit_warn(out, "", errno_label(errno, errbuf, sizeof(errbuf)));
  }

  closedir(dir);
  emit_done(out, count);
}

/* ------------------------------------------------------------------ */
/* Tiny request parser: pulls the "cmd" value out of a request line,  */
/* e.g. {"cmd":"list","path":"..."} -> "list", {"cmd":"shutdown"} ->  */
/* ------------------------------------------------------------------ */

static int extract_cmd_field(const char *line, char *out, size_t out_size) {
  const char *key = "\"cmd\"";
  const char *p = strstr(line, key);
  if (!p) return -1;

  p += strlen(key);
  while (*p == ' ' || *p == ':') p++;
  if (*p != '"') return -1;
  p++;

  size_t oi = 0;
  while (*p && *p != '"') {
    if (oi + 1 >= out_size) return -1;
    out[oi++] = *p++;
  }
  if (*p != '"') return -1;
  out[oi] = '\0';
  return 0;
}

/* ------------------------------------------------------------------ */
/* Tiny request parser: pulls the "path" value out of                 */
/* {"cmd":"list","path":"..."}.                                       */
/* Handles \" and \\ escapes within the path string; anything else    */
/* is passed through raw (filenames are UTF-8, assumption the         */
/* code makes on the output side).                                    */
/* ------------------------------------------------------------------ */

static int extract_path_field(const char *line, char *out, size_t out_size) {
  const char *key = "\"path\"";
  const char *p = strstr(line, key);
  if (!p) return -1;

  p += strlen(key);
  while (*p == ' ' || *p == ':') p++;
  if (*p != '"') return -1;
  p++;

  size_t oi = 0;
  while (*p && *p != '"') {
    if (*p == '\\' && *(p + 1)) {
      p++;
      char c = *p;
      if (c == 'n')
        c = '\n';
      else if (c == 't')
        c = '\t';
      else if (c == 'r')
        c = '\r';
      if (oi + 1 >= out_size) return -1;
      out[oi++] = c;
      p++;
    } else {
      if (oi + 1 >= out_size) return -1;
      out[oi++] = *p;
      p++;
    }
  }
  if (*p != '"') return -1;
  out[oi] = '\0';
  return 0;
}

/* ------------------------------------------------------------------ */
/* Per-connection handler.                                             */
/* Returns 1 if the caller should shut the whole server down after     */
/* this connection closes (client sent {"cmd":"shutdown"}), 0 otherwise*/
/* ------------------------------------------------------------------ */

static int dxls_handler(FILE *in, FILE *out) {
  char line[PATH_MAX + 64];
  if (!fgets(line, sizeof(line), in)) {
    return 0; /* client connected then disconnected without sending anything */
  }

  char cmd[32];
  if (extract_cmd_field(line, cmd, sizeof(cmd)) != 0) {
    emit_meta(out, "", 0, 0, "EINVAL: could not parse \"cmd\" from request");
    return 0;
  }

  if (strcmp(cmd, "shutdown") == 0) {
    fputs("{\"type\":\"shutdown_ack\"}\n", out);
    fflush(out);
    return 1;
  }

  if (strcmp(cmd, "list") == 0) {
    char path[PATH_MAX];
    if (extract_path_field(line, path, sizeof(path)) != 0) {
      emit_meta(out, "", 0, 0, "EINVAL: could not parse \"path\" from request");
      return 0;
    }
    list_directory(path, out);
    return 0;
  }

  emit_meta(out, "", 0, 0, "EINVAL: unknown cmd");
  return 0;
}

/* ------------------------------------------------------------------ */
/* Self-unlink: remove this binary from disk immediately on start.    */
/* The running process keeps working (inode stays alive via the open  */
/* executable mapping) until it actually exits, at which point the    */
/* kernel reclaims it - no leftover file on the device either way.    */
/* ------------------------------------------------------------------ */

static void self_unlink(void) {
  char self_path[PATH_MAX];
  ssize_t n = readlink("/proc/self/exe", self_path, sizeof(self_path) - 1);
  if (n > 0) {
    self_path[n] = '\0';
    unlink(self_path); /* ignore failure: not fatal, just means the
                           file lingers until manually removed */
  }
}

/* ------------------------------------------------------------------ */
/* Detach from the launching shell/adb session so the process survives */
/* the "shell:...  &" stream being closed right after exec, without    */
/* depending on `nohup` being present on the device (toybox usually    */
/* has it, but not guaranteed on every ROM).                           */
/*                                                                      */
/* Two parts:                                                          */
/*   1. Ignore SIGHUP - the signal a controlling terminal/session      */
/*      traditionally sends its children when it goes away. This is    */
/*      exactly what `nohup` itself does, just done in-process.        */
/*   2. setsid() - creates a brand new session with this process as    */
/*      its leader and NO controlling terminal at all. Once there's no */
/*      controlling terminal, there's no terminal-hangup path to this  */
/*      process in the first place, which is a stronger guarantee than */
/*      #1 alone (setsid() can only fail if this process is already a  */
/*      process group leader, which it isn't here since it was just    */
/*      exec'd fresh by the shell - failure is harmless either way,    */
/*      SIGHUP-ignore still covers us as a fallback). */
static void detach_from_session(void) {
  signal(SIGHUP, SIG_IGN);
  setsid(); /* return value intentionally ignored: on failure we just
               fall back to the SIGHUP-ignore above, still functional */
}

/* ------------------------------------------------------------------ */
/* Serve loop with idle timeout, built directly on dxsock_listen()     */
/* instead of dxsock_accept_loop(), since we need poll()'s timeout     */
/* to know when to give up and exit - dxsock_accept_loop() blocks on   */
/* accept() forever by design (see dxsock.h) and has no timeout        */
/* concept, which is correct for it to stay protocol/lifecycle-        */
/* agnostic; the timeout policy belongs here in the binary.            */
/* ------------------------------------------------------------------ */

static void serve_with_idle_timeout(int listen_fd, int idle_timeout_secs) {
  for (;;) {
    struct pollfd pfd;
    pfd.fd = listen_fd;
    pfd.events = POLLIN;

    int ret = poll(&pfd, 1, idle_timeout_secs * 1000);

    if (ret == 0) {
      fprintf(stderr, "[dxls] idle timeout (%ds) reached, exiting\n",
              idle_timeout_secs);
      return;
    }
    if (ret < 0) {
      if (errno == EINTR) continue;
      fprintf(stderr, "[dxls] poll() failed: %s\n", strerror(errno));
      return;
    }

    int conn_fd = accept(listen_fd, NULL, NULL);
    if (conn_fd < 0) {
      if (errno == EINTR) continue; /* not fatal, keep serving */
      continue; /* transient accept error; poll() will tell us if
                   the listening socket is genuinely dead */
    }

    int dup_fd = dup(conn_fd);
    if (dup_fd < 0) {
      close(conn_fd);
      continue;
    }

    FILE *in = fdopen(conn_fd, "r");
    if (!in) {
      close(conn_fd);
      close(dup_fd);
      continue;
    }

    FILE *out = fdopen(dup_fd, "w");
    if (!out) {
      fclose(in); /* also closes conn_fd */
      close(dup_fd);
      continue;
    }

    fprintf(stderr, "[dxls] client connected\n");
    int shutdown_requested = dxls_handler(in, out);
    fprintf(stderr, "[dxls] client disconnected\n");

    fclose(in);  /* closes conn_fd */
    fclose(out); /* closes dup_fd */

    if (shutdown_requested) {
      fprintf(stderr, "[dxls] shutdown requested by client, exiting\n");
      return;
    }

    /* loop back to poll(): idle timer effectively resets here,
     * measuring time between the END of one connection and the
     * START of waiting for the next */
  }
}

int main(void) {
  setvbuf(stderr, NULL, _IONBF, 0);

  self_unlink();
  detach_from_session();

  signal(SIGPIPE, SIG_IGN); /* writes to a client that vanished mid-response
                                should fail the write, not kill the process */

  fprintf(stderr, "[dxls] starting, binding abstract socket '%s'\n",
          DXLS_SOCKET_NAME);

  int fd = dxsock_listen(DXLS_SOCKET_NAME);
  if (fd < 0) {
    fprintf(stderr, "[dxls] dxsock_listen failed: %s\n", strerror(errno));
    return 1;
  }

  fprintf(stderr, "[dxls] listening, idle timeout = %ds\n",
          DXLS_IDLE_TIMEOUT_SECS);
  serve_with_idle_timeout(fd, DXLS_IDLE_TIMEOUT_SECS);

  fprintf(stderr, "[dxls] exiting\n");
  return 0;
}