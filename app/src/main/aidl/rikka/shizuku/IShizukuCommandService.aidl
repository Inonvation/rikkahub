package rikka.shizuku;

interface IShizukuCommandService {

    // Reserved destroy method defined by Shizuku server
    void destroy() = 16777114;

    // Execute a command in the shizuku process (shell identity), return exit code
    int execute(in String[] cmd, long timeoutMillis) = 1;

    int getExitCode() = 2;

    String getStdout() = 3;

    String getStderr() = 4;
}