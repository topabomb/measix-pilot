# PRoot Binary Provenance

## Sources and local patch

- PRoot: `v5.1.107.96`, commit `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`.
- Source archive and dependency checksums: `workspace/proot-lock.json`.
- Termux recipe reference: commit `08b49b3ce00b1e14a3a0365200f30e50f8dfafe1`,
  [PRoot](https://github.com/termux/termux-packages/blob/08b49b3ce00b1e14a3a0365200f30e50f8dfafe1/packages/proot/build.sh)
  and [libtalloc](https://github.com/termux/termux-packages/blob/08b49b3ce00b1e14a3a0365200f30e50f8dfafe1/packages/libtalloc/build.sh).
- Local termination and syscall compatibility patch: `workspace/tools/patches/proot-termination.patch`, also hash-locked.

The earlier imports came from upstream app commit `f4508dfac2255cf83e75859a8fe37dd7da6778a3`.
The shipped executables and loader companions are now local source builds; they are not claimed
byte-identical to those imports. The direct build uses the same fixed source versions, Android API
floor and static dependencies, with the Termux talloc cross answers. It does not run the entire
Termux packaging system.

## Process termination

Android `Process.destroyForcibly()` sends SIGTERM. The upstream PRoot ignores SIGTERM and only
cleans its tracees for SIGQUIT. The local patch makes both signals request termination through
PRoot's existing tracee registry and waitpid loop. PRoot blocks these signals before creating the
first tracee; the guest restores the inherited signal mask, while the tracer restores it only after
registering the child and installing its handlers. Early cancellation therefore remains pending.

The termination request remains set while the loop drains events, so a child first discovered
through a pending fork/clone event is also killed and reaped. Already reaped PIDs are not signaled.
Normal termination handlers avoid stdio. The shell adapter retains ProcessBuilder and separate
stdin/stdout/stderr; WorkspacePtySession sends SIGTERM to its existing PRoot PID. PRoot remains
the only owner of traced guest processes. No signal-forwarding shell, PID reflection, timer,
seccomp override or replacement process supervisor is used.

## Rebuild and verification

The patch also handles Android's SIGSYS rejection of musl's raw `fork` by restarting the equivalent
`clone(SIGCHLD, 0, 0, 0, 0)` through PRoot's existing seccomp path. The parent uses the existing
register restoration; the new child restores only its original argument registers before its first
resume, preserving its return value and syscall state. A failed restoration terminates that child
and leaves reaping to the same tracee lifecycle. Normal clone/thread creation is unchanged.
The fork event captures a separate argument snapshot before `PTRACE_GETEVENTMSG`. When the kernel
omits the child's PID, the snapshot travels with the pending-child record, including the vfork
adoption path. Delayed registration never reads the parent's subsequently restored registers.
Consumption clears the pending restore flag, including invalid-PID and already-registered exits.

`workspace/tools/build-proot.py` requires Python 3.12+, make, patch, gawk, and the Linux x86_64 Android
NDK r29 (`29.0.14206865`). The NDK revision, API 24, source/dependency hashes, patch hash and final
artifact hashes are recorded in `workspace/proot-lock.json`. Native ELF segments support 16 KiB
host pages; guest Rootfs compatibility remains a separate admission check.

```sh
python3 workspace/tools/build-proot.py \
  --ndk /path/to/android-ndk-r29 \
  --work-dir /path/to/build-parent \
  --cache-dir /path/to/source-cache \
  --output-dir /path/to/verified-output
```

All three directories are explicit. The script creates and owns a unique child of `--work-dir`,
places temporary files and build caches there, and removes only that child. Output must be outside
the repository. Source checksums and compiler revision are checked before building. The default
mode rejects output differing from the manifest and never installs artifacts into the repository.
`--candidate` emits proposed hashes for an intentional native update, without rewriting the
manifest; it is not a verification pass. `--keep-work-dir` retains exact patched sources, dependency
objects and configuration for inspection and relinking.

Builds use deterministic archives, a fixed source date and normalized source paths. On 2026-10-10,
both ABI exec/loader pairs were reproduced byte-for-byte in independent source/build directories
using the finalized compiler and linker flags. All four hashes match the shipped files and manifest.
Both rebuilt loader companions also match their earlier imported bytes. Host builds and ELF checks
do not replace Android device verification.

On 2026-10-25, `.96` with the updated pending-child snapshot patch was built in two independent
source/build directories using the verified r29 Linux archive. All four resulting files were
byte-identical between builds; both loader hashes stayed unchanged. The manifest records the new
exec hashes and patch hash. `tools/test-proot-child-snapshot.py` compiles the actual child-registration
branches with host fault injection and validates snapshot capture/dispatch under missing PID and
delayed-parent scenarios. Its ptrace, process identity and attach substitutes do not prove kernel
register restoration or Android process cleanup.

## Runtime contract and device acceptance

Both ABIs retain the shared ProotLaunchSpec: root-id, link2symlink, kill-on-exit, `-k 4.14.0`, existing
bind mounts, PWD and explicit environment. `PROOT_LOADER` selects the separately built loader.
`--kill-on-exit` terminates remaining tracees when the initial guest exits; terminating the tracer
with SIGKILL bypasses its cleanup and is not the workspace shutdown protocol.

The previous `.92` x86_64 pair passed Android API 30 / 4 KiB Alpine 3.22.6 and Ubuntu 24.04.3 acceptance: shell/file operations, distinct
stdin/stdout/stderr, long output, bounded timeout and cancellation, cancellation during startup,
background process disappearance, and two independently closed PTYs. Tests on x86_64 AVDs do not
prove arm64 physical-device interoperability. The 16 KiB host rejection of an incompatible 4 KiB
guest archive passed on API 37.1 and is separate from successful execution of compatible guests.
Those results do not validate the `.96` upgrade. The `.96` build with the Android creat SIGSYS
fallback separately passed API 36 / x86_64 / 4 KiB Ubuntu 26.04.1 shell, PTY, cancellation,
fork, executable/descriptor and offline dpkg installation/upgrade/removal checks. Its memfd
result was native permission-refusal equivalence, not successful reopening. Arm64, older API
and fault-kernel runtime validation remain separate requirements.

`tools/proot-fork-registers-x86_64.S` is a libc-independent Linux probe. Build it with the command
in its header and supply its URL as `prootForkProbeUrl` alongside `prootRootfsUrl` to
`WorkspaceProotAndroidTest`. The acceptance test runs it 32 times: both fork return paths retain
all six argument registers, the child can make a subsequent syscall, and the parent observes its
child's successful exit through waitpid. This complements the ordinary shell/PTY cancellation cases.

`workspace/tools/build-proot-behavior-probe.py --ndk /path/to/android-ndk-r29 --output-dir /outside/repo`
builds static Bionic probes for both ABIs. Supply the matching file as `prootBehaviorProbeUrl` and
its companion dynamic PIE as `prootNativeProbeUrl`, plus
the matching guest archive as `prootRootfsUrl` to
`WorkspaceProotAndroidTest.matchingNativeProbeChecksL2sExecutableNamesDescriptorsAndChildExecution`.
The independent test checks PRoot-created fake hard links, initial executable lookup, shell exec,
execveat, O_NOFOLLOW/O_PATH descriptor names, regular-file/memfd access-mode changes and vfork/spawn.
It also checks raw creat creation, permissions and truncation through the Android SIGSYS fallback.
Descriptor reopening is compared with the dynamic PIE under the same application UID and SELinux
domain. Only complete success or the exact native memfd first-readonly EACCES refusal is accepted;
other failures, mismatched access modes and unexpected successful permission expansion fail.
A matched refusal proves policy preservation, not successful memfd reopening on that device.
It requires API 28 for Bionic posix_spawn; missing fixtures or older hosts are reported as skipped.
This does not replace Ubuntu 26.04 dpkg/rust-coreutils acceptance, API 24–27 testing or fault-kernel
interoperability. The product itself retains its API 24 build floor.

`WorkspaceProotAndroidTest.ubuntu2604InstallsReplacesAndPurgesLocalPackageUsingRustCoreutils`
requires `prootUbuntu2604=true` and a matching official Ubuntu 26.04 `prootRootfsUrl`.
It checks the distribution and the shipped `/usr/bin/coreutils` implementations of `ls`, `cp`, `rm`, `ln`, builds two versions
of a local package, installs/upgrades them with dpkg, verifies post-install copy/link contents,
and purges the package. Post-install actions explicitly select those Rust subcommands because
the distribution may still select GNU cp/rm by default. The random temporary workspace must be deleted successfully.
It does not fetch packages or claim other ABIs passed; missing opt-in is an explicit skip.

## Distribution materials

- PRoot: GPL-2.0-or-later; retain the corresponding source, exact local patch and build scripts,
  including the GPL text or a compliant source offer.
- libtalloc 2.4.3: LGPL-3.0-or-later, statically linked. A distribution must provide relinkable
  application/object material and applicable installation information, or another compliant
  linkage model. Keep the build directory when preparing those materials.
- libandroid-shmem 0.7: BSD-3-Clause. Its complete notice is in `THIRD_PARTY_NOTICES.md`.

The source URLs, reproducible build and hash manifest establish provenance. They do not alone
complete the separate release distribution-materials gate.
