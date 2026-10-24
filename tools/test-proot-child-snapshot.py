#!/usr/bin/env python3
"""Exercise the patched PRoot child-registration branches with host fault injection.

Pass an already patched source directory. The actual C branch implementations are
compiled with a small host fixture; ptrace, /proc identity reads and attach_child
are substituted. This proves snapshot capture/dispatch, not kernel register
restoration, Android termination, or full tracer interoperability.
"""

import argparse
from pathlib import Path
import re
import subprocess
import tempfile


def function(source, name):
    match = re.search(r"^[A-Za-z][^\n;{}]*\b" + name + r"\([^\n;{}]*\)\n\{", source, re.M)
    if match is None:
        raise RuntimeError("Missing function definition: " + name)
    start = source.index("{", match.start())
    depth = 1
    pos = start + 1
    while depth:
        if pos >= len(source):
            raise RuntimeError("Unterminated function: " + name)
        if source[pos] == "{":
            depth += 1
        elif source[pos] == "}":
            depth -= 1
        pos += 1
    return source[match.start():pos] + "\n"


PRELUDE = r"""
#define _GNU_SOURCE
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <signal.h>
#include <sched.h>
#include <sys/queue.h>
#include <sys/types.h>
#include <unistd.h>
typedef unsigned long word_t;
enum { CURRENT, ORIGINAL, ORIGINAL_SECCOMP_REWRITE, NB_REG_VERSION };
enum { SYSARG_1, SYSARG_RESULT, STACK_POINTER };
enum { PR_clone, PR_clone3, PR_fork, PR_vfork, PR_other };
enum { SIGSTOP_PENDING = 1 };
struct user_regs_struct { word_t args[6]; };
typedef struct tracee {
    LIST_ENTRY(tracee) link;
    pid_t pid;
    void *exe;
    bool terminated;
    int sigstop;
    bool pending_child;
    word_t pending_clone_flags, pending_child_sp;
    bool pending_restore_fork_child_regs;
    struct user_regs_struct pending_fork_child_regs;
    bool restore_original_regs_after_seccomp_event;
    struct user_regs_struct _regs[NB_REG_VERSION];
    bool clone_stripped_newns, clone_stripped_newnet;
    int current_sysnum, original_sysnum;
    word_t flags, result, stack;
} Tracee;
static LIST_HEAD(tracees_head, tracee) tracees;
static Tracee parent, child;
static unsigned long event_pid;
static int event_status, fetch_status, attach_status, attach_count;
static bool related, tracer_matches, attach_has_regs;
static pid_t attached_pid;
static struct user_regs_struct attached_regs;
static int fetch_regs(Tracee *t) { (void)t; return fetch_status; }
static int get_sysnum(Tracee *t, int kind) {
    return kind == CURRENT ? t->current_sysnum : t->original_sysnum;
}
static word_t peek_reg(Tracee *t, int kind, int reg) {
    (void)kind;
    return reg == SYSARG_1 ? t->flags : reg == SYSARG_RESULT ? t->result : t->stack;
}
static word_t peek_word(Tracee *t, word_t address) { (void)address; return t->flags; }
static word_t new_child_stack(Tracee *t) { return t->stack; }
static bool is_pending_child_of(const Tracee *p, Tracee *c) {
    return related && p == &parent && c == &child;
}
static int read_proc_status_ids(pid_t pid, pid_t *tgid, pid_t *ppid, pid_t *tracer) {
    (void)pid; *tgid = 1; *ppid = 1; *tracer = tracer_matches ? getpid() : getpid() + 1;
    return 0;
}
static Tracee *get_tracee(Tracee *p, pid_t pid, bool create) {
    (void)p; (void)create; return pid == child.pid ? &child : NULL;
}
static int test_ptrace(int request, pid_t pid, void *unused, unsigned long *value) {
    (void)request; (void)pid; (void)unused; *value = event_pid; return event_status;
}
#define ptrace test_ptrace
#define PTRACE_GETEVENTMSG 1
#define VERBOSE(...) ((void)0)
#define note(...) ((void)0)
static int attach_child(Tracee *p, word_t flags, pid_t pid,
        const struct user_regs_struct *regs) {
    (void)p; (void)flags;
    ++attach_count; attached_pid = pid; attach_has_regs = regs != NULL;
    if (regs) attached_regs = *regs;
    return attach_status;
}
static void reset_fixture(void) {
    memset(&parent, 0, sizeof(parent)); memset(&child, 0, sizeof(child));
    memset(&attached_regs, 0, sizeof(attached_regs));
    LIST_INIT(&tracees); LIST_INSERT_HEAD(&tracees, &child, link); LIST_INSERT_HEAD(&tracees, &parent, link);
    parent.pid = 100; parent.exe = &parent;
    child.pid = 201; child.sigstop = SIGSTOP_PENDING;
    parent.current_sysnum = parent.original_sysnum = PR_clone;
    parent.flags = SIGCHLD; parent.result = child.pid; parent.stack = 0x1234;
    parent.restore_original_regs_after_seccomp_event = true;
    for (int i = 0; i < 6; ++i) parent._regs[ORIGINAL_SECCOMP_REWRITE].args[i] = 0xfeed + i;
    event_pid = child.pid; event_status = fetch_status = attach_status = attach_count = 0;
    related = tracer_matches = true; attach_has_regs = false; attached_pid = -1;
}
static void assert_snapshot(void) {
    assert(attach_count == 1 && attached_pid == child.pid && attach_has_regs);
    for (int i = 0; i < 6; ++i) assert(attached_regs.args[i] == 0xfeedUL + i);
}
"""

CASES = r"""
int main(void) {
    /* Normal GETEVENTMSG path hands over a complete six-register snapshot. */
    reset_fixture(); assert(new_child(&parent, SIGCHLD) == 0); assert_snapshot();

    /* Both missing and failed event messages retain the captured arguments. */
    for (int failure = 0; failure < 2; ++failure) {
        reset_fixture(); event_pid = 0; event_status = failure ? -1 : 0;
        assert(new_child(&parent, SIGCHLD) == 0);
        assert(parent.pending_child && parent.pending_restore_fork_child_regs && attach_count == 0);
        parent.restore_original_regs_after_seccomp_event = false;
        memset(&parent._regs[ORIGINAL_SECCOMP_REWRITE], 0xba, sizeof(struct user_regs_struct));
        resolve_pending_child(&parent); assert_snapshot();
        assert(!parent.pending_child && !parent.pending_restore_fork_child_regs);
    }

    /* An unrelated syscall cannot supply a child PID or consume its snapshot. */
    reset_fixture(); event_pid = 0; new_child(&parent, SIGCHLD);
    parent.original_sysnum = PR_other; resolve_pending_child(&parent);
    assert(attach_count == 0 && parent.pending_child && parent.pending_restore_fork_child_regs);
    parent.original_sysnum = PR_clone; resolve_pending_child(&parent); assert_snapshot();

    /* Invalid/reused PID and already-registered children consume pending state. */
    for (int failure = 0; failure < 3; ++failure) {
        reset_fixture(); event_pid = 0; new_child(&parent, SIGCHLD);
        if (failure == 0) parent.result = 0;
        if (failure == 1) tracer_matches = false;
        if (failure == 2) child.exe = &child;
        resolve_pending_child(&parent);
        assert(attach_count == 0 && !parent.pending_child && !parent.pending_restore_fork_child_regs);
        /* A subsequent ordinary clone must not inherit a stale restore flag. */
        child.exe = NULL; parent.restore_original_regs_after_seccomp_event = false;
        event_pid = child.pid; new_child(&parent, SIGCHLD);
        assert(attach_count == 1 && !attach_has_regs);
    }

    reset_fixture(); event_pid = 0; new_child(&parent, SIGCHLD); attach_status = -12;
    resolve_pending_child(&parent); assert_snapshot();
    assert(!parent.pending_child && !parent.pending_restore_fork_child_regs);

    /* Failed fetch and native clone3 never capture stale rewrite registers. */
    for (int variant = 0; variant < 2; ++variant) {
        reset_fixture(); event_pid = 0;
        if (variant == 0) fetch_status = -1; else parent.current_sysnum = PR_clone3;
        new_child(&parent, SIGCHLD); assert(!parent.pending_restore_fork_child_regs);
        resolve_pending_child(&parent); assert(attach_count == 1 && !attach_has_regs);
    }

    /* vfork adoption does not wait for the parent's syscall exit. */
    reset_fixture(); event_pid = 0; parent.current_sysnum = PR_vfork;
    new_child(&parent, CLONE_VFORK | SIGCHLD);
    assert(attach_count == 1 && !attach_has_regs && !parent.pending_child);

    /* Exercise snapshot consumption through the held-child dispatcher itself. */
    reset_fixture(); parent.pending_child = true; parent.pending_clone_flags = CLONE_VFORK;
    parent.pending_restore_fork_child_regs = true;
    parent.pending_fork_child_regs = parent._regs[ORIGINAL_SECCOMP_REWRITE];
    memset(&parent._regs[ORIGINAL_SECCOMP_REWRITE], 0xba, sizeof(struct user_regs_struct));
    adopt_held_children(); assert_snapshot(); assert(!parent.pending_restore_fork_child_regs);

    /* Unrelated or terminated tracees must not be adopted. */
    for (int variant = 0; variant < 3; ++variant) {
        reset_fixture(); parent.pending_child = true; parent.pending_clone_flags = CLONE_VFORK;
        if (variant == 0) related = false;
        if (variant == 1) parent.terminated = true;
        if (variant == 2) child.terminated = true;
        adopt_held_children(); assert(attach_count == 0 && parent.pending_child);
    }
    puts("PRoot child snapshot: 15 fault-injection scenarios passed (attach/ptrace substituted)");
    return 0;
}
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--cc", default="cc")
    args = parser.parse_args()
    source = (args.source / "src/tracee/tracee.c").read_text(encoding="utf-8")
    actual = "\n".join(function(source, name) for name in (
        "take_pending_fork_child_regs", "adopt_held_children", "resolve_pending_child", "new_child",
    ))
    with tempfile.TemporaryDirectory(prefix="proot-child-snapshot-") as temporary:
        root = Path(temporary)
        fixture = root / "snapshot.c"
        fixture.write_text(PRELUDE + actual + CASES, encoding="utf-8")
        executable = root / "snapshot"
        subprocess.run([args.cc, "-std=c11", "-Wall", "-Wextra", "-Werror", "-O1", "-g",
                        "-fsanitize=address,undefined", str(fixture), "-o", str(executable)], check=True)
        subprocess.run([str(executable)], check=True)


if __name__ == "__main__":
    main()
