package dev.syed.hermeslocal.engine

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * File operations that never follow symlinks. A rootfs is full of absolute symlinks (/proc, /dev,
 * /usr/lib/...) that resolve to the HOST when seen from outside proot, so Kotlin's
 * File.deleteRecursively / copyRecursively must not be used on it.
 */
internal object NativeFs {
  private const val MODE_RWX_OWNER = 0x1C0 // 0700

  fun isSymlink(file: File): Boolean = try {
    OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
  } catch (_: ErrnoException) {
    false
  }

  fun exists(file: File): Boolean = try {
    Os.lstat(file.path)
    true
  } catch (_: ErrnoException) {
    false
  }

  /** Bytes [root] occupies on disk (st_blocks), without crossing symlinks. 0 when missing. */
  fun du(root: File): Long {
    val st = try {
      Os.lstat(root.path)
    } catch (_: ErrnoException) {
      return 0L
    }
    var total = st.st_blocks * 512
    if (OsConstants.S_ISDIR(st.st_mode)) root.list()?.forEach { total += du(File(root, it)) }
    return total
  }

  /** Deletes [root] and everything under it without crossing symlinks. */
  fun deleteTree(root: File) {
    val st = try {
      Os.lstat(root.path)
    } catch (_: ErrnoException) {
      return
    }
    if (OsConstants.S_ISDIR(st.st_mode)) {
      // A payload can contain read-only directories; make them writable so their entries go.
      try {
        Os.chmod(root.path, (st.st_mode and 0xFFF) or MODE_RWX_OWNER)
      } catch (_: ErrnoException) {
      }
      root.list()?.forEach { deleteTree(File(root, it)) }
    }
    try {
      Os.remove(root.path)
    } catch (_: ErrnoException) {
    }
  }

  /**
   * Moves [from] to [to] (same filesystem). Falls back to a symlink-preserving copy + delete when a
   * rename is impossible.
   */
  fun move(from: File, to: File) {
    to.parentFile?.mkdirs()
    if (exists(to)) deleteTree(to)
    try {
      Os.rename(from.path, to.path)
      return
    } catch (_: ErrnoException) {
    }
    copyTree(from, to)
    deleteTree(from)
  }

  fun copyTree(from: File, to: File) {
    val st = Os.lstat(from.path)
    when {
      OsConstants.S_ISLNK(st.st_mode) -> Os.symlink(Os.readlink(from.path), to.path)
      OsConstants.S_ISDIR(st.st_mode) -> {
        to.mkdirs()
        from.list()?.forEach { copyTree(File(from, it), File(to, it)) }
        Os.chmod(to.path, (st.st_mode and 0xFFF) or MODE_RWX_OWNER)
      }
      OsConstants.S_ISREG(st.st_mode) -> {
        from.inputStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        Os.chmod(to.path, st.st_mode and 0xFFF)
      }
      else -> Unit // sockets, fifos, devices: not part of persistent state
    }
  }
}

/** The pid of a child process. java.lang.Process.pid() is not on every Android version. */
internal fun Process.pidCompat(): Int? = try {
  val field = javaClass.getDeclaredField("pid")
  field.isAccessible = true
  field.getInt(this)
} catch (_: Throwable) {
  null
}

internal fun Process.isAliveCompat(): Boolean = try {
  exitValue()
  false
} catch (_: IllegalThreadStateException) {
  true
}

/**
 * SIGTERM to proot's direct children (the guest's first process, e.g. hermes-local-start, which then stops
 * its own processes), wait up to [graceMs] for proot to exit with them, then [terminate]. Children are
 * found by ppid in /proc (an app sees its own processes).
 */
internal fun Process.terminateGuestFirst(graceMs: Long = 8000) {
  val pid = pidCompat() ?: return terminate(graceMs)
  val children = File("/proc").list()?.filter { it.firstOrNull()?.isDigit() == true }?.mapNotNull { c ->
    runCatching {
      val stat = File("/proc/$c/stat").readText()
      c.toInt().takeIf { stat.substring(stat.lastIndexOf(')') + 2).split(' ')[1].toInt() == pid }
    }.getOrNull()
  }.orEmpty()
  children.forEach { try { Os.kill(it, OsConstants.SIGTERM) } catch (_: ErrnoException) {} }
  val deadline = System.currentTimeMillis() + if (children.isEmpty()) 0 else graceMs
  while (isAliveCompat() && System.currentTimeMillis() < deadline) Thread.sleep(100)
  terminate(1500)
}

/**
 * SIGTERM, then SIGKILL after [graceMs]. proot runs with --kill-on-exit, so when proot dies every
 * guest process it traces dies with it.
 */
internal fun Process.terminate(graceMs: Long = 3000) {
  val pid = pidCompat()
  if (pid != null) {
    try {
      Os.kill(pid, OsConstants.SIGTERM)
    } catch (_: ErrnoException) {
    }
    val deadline = System.currentTimeMillis() + graceMs
    while (isAliveCompat() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    if (isAliveCompat()) {
      try {
        Os.kill(pid, OsConstants.SIGKILL)
      } catch (_: ErrnoException) {
      }
    }
  }
  destroy()
}
