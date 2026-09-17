#!/bin/bash
# Manual PRoot build driver for Windows (Git Bash) — no `make` required.
# Faithfully replicates deps/proot/src/GNUmakefile using the NDK toolchain
# directly. Uses PROOT_UNBUNDLE_LOADER to skip loader-embedding (objcopy),
# since the app ships an external loader in jniLibs (loader extraction is
# dead on Android 10+). On aarch64 HAS_POKEDATA_WORKAROUND is true, so
# loader/loader-info.o IS still required (built via readelf+awk).
set -e

NDK_HOME="/c/Users/mt/AppData/Local/Android/Sdk/ndk/28.2.13676358"
TBIN="$NDK_HOME/toolchains/llvm/prebuilt/windows-x86_64/bin"
API=26
CC="$TBIN/aarch64-linux-android${API}-clang"
AR="$TBIN/llvm-ar"
RANLIB="$TBIN/llvm-ranlib"
STRIP="$TBIN/llvm-strip"
READELF="$TBIN/llvm-readelf"
OBJDUMP="$TBIN/llvm-objdump"

ROOT="/d/Code/IM/OpenMinis/OpenMinis-main"
PROOT="$ROOT/deps/proot/src"
TALLOC="$ROOT/deps/talloc"
BUILD="$ROOT/deps/build/proot-android"
OUT_ASSET="$ROOT/src/android/app/src/main/assets/proot-aarch64"
OUT_JNI="$ROOT/src/android/app/src/main/jniLibs/arm64-v8a/libproot.so"

mkdir -p "$BUILD/talloc-obj" "$BUILD/obj"

echo "## [1/7] libtalloc.a (static)"
$CC -c "$TALLOC/talloc.c" -o "$BUILD/talloc-obj/talloc.o" \
    -I"$TALLOC" -fPIC -O2 -Wall -std=gnu99 \
    -DHAVE_STDARG_H=1 -DHAVE_VA_COPY=1 -DHAVE_UNISTD_H=1 -DHAVE_INTPTR_T=1
$AR rcs "$BUILD/libtalloc.a" "$BUILD/talloc-obj/talloc.o"
$RANLIB "$BUILD/libtalloc.a"

cd "$PROOT"
CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -DARG_MAX=131072 -I$TALLOC"
# PROOT_UNBUNDLE_LOADER compiles out the embedded-loader symbols
# (_binary_loader_exe_*); the app supplies the loader via PROOT_LOADER env,
# and embedded extraction is dead on Android 10+ (W^X). The macro value is
# only a fallback path, never used at runtime when PROOT_LOADER is set.
CFLAGS='-O2 -Wall -Wextra -fPIE -DPROOT_UNBUNDLE_LOADER="/var/lib/proot"'
LDFLAGS="-Wl,-z,noexecstack -pie -L$BUILD -ltalloc"

echo "## [2/7] build.h (feature checks)"
HAVE=""
if $CC $CPPFLAGS $CFLAGS -c .check_process_vm.c -o "$BUILD/obj/cpvm.o" 2>/dev/null && \
   $CC -o "$BUILD/obj/cpvm" "$BUILD/obj/cpvm.o" $LDFLAGS 2>/dev/null; then
    HAVE="${HAVE}#define HAVE_PROCESS_VM
"
fi
if $CC $CPPFLAGS $CFLAGS -c .check_seccomp_filter.c -o "$BUILD/obj/sec.o" 2>/dev/null && \
   $CC -o "$BUILD/obj/sec" "$BUILD/obj/sec.o" $LDFLAGS 2>/dev/null; then
    HAVE="${HAVE}#define HAVE_SECCOMP_FILTER
"
fi
{
    echo "/* auto-generated, edit at own risk */"
    echo "#ifndef BUILD_H"
    echo "#define BUILD_H"
    printf "%s" "$HAVE"
    echo "#endif /* BUILD_H */"
} > build.h
echo "   build.h:"; cat build.h | sed 's/^/     /'

echo "## [3/7] loader/loader (aarch64 LOADER_ADDRESS=0x2000000000)"
mkdir -p loader
$CC $CPPFLAGS $CFLAGS -fPIC -ffreestanding -c loader/loader.c -o loader/loader.o
$CC $CPPFLAGS $CFLAGS -fPIC -ffreestanding -c loader/assembly.S -o loader/assembly.o
$CC -o loader/loader loader/loader.o loader/assembly.o \
    -static -nostdlib -Wl,-Ttext=0x2000000000,--rosegment,-z,noexecstack

echo "## [4/7] loader/loader-info.c (readelf|awk)"
$READELF -s loader/loader | awk -f loader/loader-info.awk > loader/loader-info.c
$CC $CPPFLAGS $CFLAGS -c loader/loader-info.c -o loader/loader-info.o

echo "## [5/7] compile objects"
SRCS="cli/cli cli/proot cli/note \
execve/enter execve/exit execve/shebang execve/elf execve/ldso execve/auxv execve/aoxp \
path/binding path/glue path/canon path/f2fs-bug path/path path/proc path/temp \
syscall/seccomp syscall/syscall syscall/chain syscall/enter syscall/exit syscall/sysnum syscall/socket syscall/heap syscall/rlimit \
tracee/tracee tracee/mem tracee/reg tracee/event tracee/seccomp tracee/statx \
ptrace/ptrace ptrace/user ptrace/wait \
extension/extension extension/ashmem_memfd/ashmem_memfd extension/kompat/kompat \
extension/fake_id0/chown extension/fake_id0/chroot extension/fake_id0/getsockopt extension/fake_id0/sendmsg extension/fake_id0/socket extension/fake_id0/open extension/fake_id0/unlink extension/fake_id0/rename extension/fake_id0/chmod extension/fake_id0/utimensat extension/fake_id0/access extension/fake_id0/exec extension/fake_id0/link extension/fake_id0/symlink extension/fake_id0/mk extension/fake_id0/stat extension/fake_id0/helper_functions extension/fake_id0/fake_id0 \
extension/hidden_files/hidden_files extension/mountinfo/mountinfo extension/port_switch/port_switch \
extension/sysvipc/sysvipc extension/sysvipc/sysvipc_msg extension/sysvipc/sysvipc_sem extension/sysvipc/sysvipc_shm \
extension/link2symlink/link2symlink extension/fix_symlink_size/fix_symlink_size extension/native_offload/native_offload"
OBJS=""
for c in $SRCS; do
    d=$(dirname "$c"); mkdir -p "$d"
    $CC $CPPFLAGS $CFLAGS -c "$c.c" -o "$c.o"
    OBJS="$OBJS $c.o"
done
OBJS="$OBJS loader/loader-info.o"

echo "## [6/7] link proot (static talloc)"
$CC -o proot $OBJS $LDFLAGS
$STRIP proot

echo "## [7/7] verify + install"
if $READELF -d proot | grep -qi 'NEEDED.*talloc'; then
    echo "FAIL: proot still has libtalloc DT_NEEDED"; exit 1
fi
if $READELF -d proot | grep -qi 'NEEDED.*libandroid-shmem'; then
    echo "FAIL: proot has libandroid-shmem DT_NEEDED"; exit 1
fi
echo "   NEEDED entries:"; $READELF -d proot | grep NEEDED | sed 's/^/     /'
echo "   size: $(wc -c < proot) bytes"
cp -f proot "$OUT_ASSET"
cp -f proot "$OUT_JNI"
echo "## DONE -> $OUT_JNI"
