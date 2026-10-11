package me.rerere.workspace

import java.io.File

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.isDirectory || context.linuxDir.list()?.isNotEmpty() != true) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val executable = File(nativeLibraryDir, ProotLaunchSpec.PROOT_EXEC)
        if (!executable.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${executable.absolutePath}",
            )
        }
        val loader = File(nativeLibraryDir, ProotLaunchSpec.PROOT_LOADER)
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        val environment = RootfsCompatibility(nativeLibraryDir).requireCompatible(context.linuxDir)
        val spec = launchSpec(context, environment)

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(spec.commandLine)
            .directory(spec.workingDirectory)
            .redirectErrorStream(false)
            .apply {
                spec.environment.forEach { (k, v) ->
                    environment()[k] = v
                }
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /** Both entry points retain PRoot's syscall translation policy from the shared launch spec. */
    internal fun buildEnvironment(loader: File, tempDir: File): Map<String, String> =
        ProotLaunchSpec.processEnvironment(loader, tempDir)

    internal fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> = launchSpec(context).let { spec ->
        require(spec.executable == proot) {
            "PRoot executable must come from the native library directory"
        }
        spec.commandLine
    }

    private fun launchSpec(
        context: WorkspaceShellContext,
        environment: RootfsEnvironment = RootfsCompatibility(nativeLibraryDir).inspectEnvironment(context.linuxDir),
    ): ProotLaunchSpec =
        ProotLaunchSpec.from(
            nativeLibraryDir = nativeLibraryDir,
            filesDir = context.filesDir,
            linuxDir = context.linuxDir,
            tempDir = context.tempDir,
            bindMounts = context.bindMounts,
            guestEnvironment = environment,
            guestCwd = context.cwd,
            command = context.command,
        )

}
