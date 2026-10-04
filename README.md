# TuxTerm

An Android terminal that runs a real Ubuntu userspace, without root.

The app ships a `proot` binary, unpacks an Ubuntu root filesystem on first
launch, and gives you a shell inside it. One terminal, a key pad, and nothing
else — no launcher, no navigation, no settings screen.

## What's in the image

The rootfs is downloaded on first run and already contains the toolchain, so
these work the moment the terminal is usable:

| | |
|---|---|
| Python | 3.12.3 |
| Node | v22.23.3 |
| npm | 10.9.9 |

Baking them in keeps `apt`, DNS and the network off the critical path. Node is
not from the distro: Ubuntu 24.04 ships 18.19.1, and much of the modern npm
ecosystem requires 22 or newer, so it comes from the upstream arm64 tarball.

## Building

```sh
./gradlew assembleDebug
```

Requires the Android SDK and NDK 26.1.10909125. Only `arm64-v8a` is built,
since the bundled `proot` and the rootfs are both aarch64.

## Design notes

A few decisions that are load-bearing rather than stylistic:

**proot runs with `--link2symlink`.** Android denies `link(2)` on app storage.
dpkg builds its `status-old` backup with it, so without the flag every install
fails with `error creating new backup file ... Permission denied`, and the
suggested `dpkg --configure -a` repair fails the same way.

**`PROOT_NO_SECCOMP` is probed, not assumed.** Forcing it on removes proot's
accelerated syscall path — measured at 21.7s against 10.0s for the same
workload. It is set only when a one-off probe finds the kernel cannot take the
filter.

**DNS is taken from the device.** The base image ships public resolvers that
mobile networks commonly drop, which turns every `apt` run into a string of
timeouts. The rootfs resolver is rewritten with the device's live nameservers
at launch.

**The key pad is paged, not taller.** Three pages of two rows: control keys,
function keys, and the session read-out. A taller pad would cost the terminal
rows, and pages of unequal height would resize the PTY on every swipe — the
shell redraws on each `SIGWINCH`.

**URLs open in the browser.** Nothing inside the rootfs can reach one:
`xdg-open` finds no browser installed and fails silently, and the platform's
`am` cannot run under proot. The app watches the terminal stream instead.

## Licensing

`libvterm` under `app/src/main/cpp/libvterm` is part of the bundled terminal
emulator and carries its own licence.
