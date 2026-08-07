/*
 * header only abstract unix domain socket server helper
 *
 * it does exactly one thing ->> bind+listen on an abstract namespace AF_UNIX
 * socket and accept() connections in a loop, handing each one to a
 * caller supplied handler as a pair of FILE* streams.
 *
 *
 *   #define DXSOCK_IMPLEMENTATION   // in exactly one .c file
 *   #include "dxsock.h"
 */

#ifndef DXSOCK_H
#define DXSOCK_H

#include <stdio.h>

typedef void (*dxsock_handler_fn)(FILE *in, FILE *out, void *user_data);

/* Creates an abstract-namespace listening socket bound to `name`.
 * Returns fd on success, -1 on failure. */
static int dxsock_listen(const char *name);

/* Blocks: accept()s connections on listen_fd one at a time, wrapping
 * each in FILE* in/out and calling handler. Loops until accept() fails
 * with a non-EINTR error (e.g. listen_fd closed by caller from another
 * thread/signal handler). EINTR is retried transparently - a reusable
 * helper shouldn't stop serving just because a signal interrupted the
 * accept() call.
 * Does NOT touch signal handlers - caller decides SIGPIPE policy etc. */
static void dxsock_accept_loop(int listen_fd, dxsock_handler_fn handler,
                               void *user_data);

#ifdef DXSOCK_IMPLEMENTATION

#include <errno.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int dxsock_listen(const char *name) {
  int fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (fd < 0) return -1;

  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0'; /* abstract namespace marker */

  size_t name_len = strlen(name);
  if (name_len >= sizeof(addr.sun_path) - 1) {
    close(fd);
    return -1;
  }
  memcpy(addr.sun_path + 1, name, name_len);
  socklen_t len =
      (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + name_len);

  if (bind(fd, (struct sockaddr *)&addr, len) != 0) {
    close(fd);
    return -1;
  }
  if (listen(fd, 1) != 0) {
    close(fd);
    return -1;
  }
  return fd;
}

static void dxsock_accept_loop(int listen_fd, dxsock_handler_fn handler,
                               void *user_data) {
  for (;;) {
    int conn_fd = accept(listen_fd, NULL, NULL);
    if (conn_fd < 0) {
      if (errno == EINTR)
        continue; /* interrupted by a signal, not a
                      real failure - keep serving */
      break;      /* genuine fatal error on the listening socket */
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

    handler(in, out, user_data);

    fclose(in);  /* closes conn_fd */
    fclose(out); /* closes dup_fd */
  }
}

#endif /* DXSOCK_IMPLEMENTATION */
#endif /* DXSOCK_H */