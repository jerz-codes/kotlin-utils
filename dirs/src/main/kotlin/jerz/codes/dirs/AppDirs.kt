package jerz.codes.dirs

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.ShlObj
import java.io.File
import java.nio.file.Paths


class AppDirs(
    private val appName: String,
    private val appAuthor: String = "jerz.codes",
    private val appVersion: String = "0.0.1"
) {
    private val homeDir: String = System.getProperty("user.home")

    fun appData(): File =
        identifyOperatingSystem()
            .let { operatingSystem ->
                when (operatingSystem) {
                    OperatingSystem.MACOS -> Paths.get(
                        homeDir,
                        "Library",
                        "Application Support",
                        appAuthor,
                        appName,
                        appVersion
                    )
                    OperatingSystem.UNIX -> Paths.get(
                        (System.getenv("XDG_DATA_HOME") ?: Paths.get(homeDir, ".local", "share").toString()),
                        appName,
                        appVersion
                    )
                    OperatingSystem.WINDOWS -> Paths.get(
                        try {
                            Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_LocalAppData)
                        } catch (e: UnsatisfiedLinkError) {
                            Shell32Util.getFolderPath(ShlObj.CSIDL_LOCAL_APPDATA)
                        },
                        appAuthor,
                        appName,
                        appVersion
                    )
                }
            }
            .toFile()

    private fun identifyOperatingSystem(): OperatingSystem =
        System.getProperty("os.name")
            .lowercase()
            .let { osName ->
                when {
                    osName.startsWith("mac os x") -> OperatingSystem.MACOS
                    osName.startsWith("windows") -> OperatingSystem.WINDOWS
                    else -> OperatingSystem.UNIX
                }
            }

}

private enum class OperatingSystem {
    MACOS, UNIX, WINDOWS
}