package run.hermes.empryo.engine

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files

/**
 * Rootfs files that depend on the phone, refreshed before every guest process (cheap: written only when
 * they change):
 * - /etc/resolv.conf from the active network's DNS servers. Android has no resolv.conf of its own, and the
 *   rootfs default (1.1.1.1, 8.8.8.8) times out on networks that block outside DNS: Bun's getaddrinfo then
 *   fails with EAI_AGAIN (test1 on Syed's phone). glibc re-reads the file when it changes.
 * - /etc/hosts and /etc/nsswitch.conf if missing (glibc needs "hosts: files dns").
 * - /usr/local/bin/scutil: Empryo names the computer in the pairing link with `scutil --get ComputerName`
 *   (else the hostname, "localhost" on Android), so this prints the phone's name.
 * - test2 leftovers: dead link2symlink links in /tmp ([dropLegacyL2sLinks]).
 */
object GuestSetup {
    /** Public fallbacks after the network's own servers. Private DNS (DoT) servers are not visible to apps. */
    private val FALLBACK = listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")
    private const val MAXNS = 3 // glibc reads at most three nameserver lines

    @Synchronized
    fun apply(paths: Paths) {
        if (!paths.rootfsInstalled()) return
        val etc = File(paths.rootfs, "etc")
        writeIfChanged(File(etc, "resolv.conf"), resolvConf(networkDns(paths.app)))
        val hosts = File(etc, "hosts")
        if (!hosts.isFile) writeIfChanged(hosts, "127.0.0.1 localhost empryo-local\n::1 localhost ip6-localhost ip6-loopback\n")
        val nss = File(etc, "nsswitch.conf")
        if (!nss.isFile || !nss.readText().lines().any { it.trimStart().startsWith("hosts:") })
            writeIfChanged(nss, "passwd: files\ngroup: files\nshadow: files\nhosts: files dns\nnetworks: files\nprotocols: files\nservices: files\n")
        val name = deviceName(paths.app).replace("'", "")
        writeIfChanged(File(paths.rootfs, "usr/local/bin/scutil"), "#!/bin/sh\necho '$name'\n", executable = true)
        dropLegacyL2sLinks(paths.rootfs)
    }

    /**
     * test2 kept proot's link2symlink store under the non-canonical rootfs path (see [Proot]); the links it made
     * resolve to nothing in the guest. A leftover phone-service socket link in /tmp then makes every later
     * phone-service give up ("reclaimed-stale"), so drop those from /tmp.
     */
    internal fun dropLegacyL2sLinks(rootfs: File) {
        val legacy = rootfs.absolutePath + "/.l2s/"
        if (rootfs.absolutePath == rootfs.canonicalPath) return
        File(rootfs, "tmp").listFiles()?.forEach { f ->
            val target = runCatching { Files.readSymbolicLink(f.toPath()).toString() }.getOrNull()
            if (target != null && target.startsWith(legacy)) f.delete()
        }
    }

    /** The active network's DNS servers, IPv4 first (as host strings, scoped IPv6 keeps its %iface). */
    fun networkDns(context: Context): List<String> = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return emptyList()
        order(lp.dnsServers)
    }.getOrDefault(emptyList())

    internal fun order(servers: List<InetAddress>): List<String> {
        val v4 = servers.filterIsInstance<Inet4Address>()
        val v6 = servers.filterIsInstance<Inet6Address>().sortedBy { it.isLinkLocalAddress } // global before fe80::
        return (v4 + v6).mapNotNull { it.hostAddress }
    }

    /** At most two of the network's servers, then public fallbacks, so a fallback is always there. */
    internal fun resolvConf(network: List<String>): String {
        val ns = (network.take(2) + FALLBACK).distinct().take(MAXNS)
        val src = if (network.isEmpty()) "no DNS servers from Android, public fallbacks only" else "Android's active network: ${network.joinToString(" ")}"
        return buildString {
            append("# Written by Empryo Local before each engine start ($src)\n")
            ns.forEach { append("nameserver ").append(it).append('\n') }
            append("options timeout:2 attempts:2 single-request-reopen\n")
        }
    }

    private fun deviceName(context: Context): String =
        runCatching { AndroidSettings.Global.getString(context.contentResolver, AndroidSettings.Global.DEVICE_NAME) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun writeIfChanged(f: File, text: String, executable: Boolean = false) {
        runCatching {
            // A symlink here would point at the host when seen from outside proot: replace it, never follow it.
            if (NativeFs.isSymlink(f)) f.delete()
            if (f.isFile && f.readText() == text) return
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.tmp")
            tmp.writeText(text)
            if (executable) tmp.setExecutable(true, false)
            tmp.renameTo(f)
        }
    }
}
