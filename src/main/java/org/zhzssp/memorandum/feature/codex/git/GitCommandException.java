package org.zhzssp.memorandum.feature.codex.git;

/** Git 命令执行失败。携带退出码与 stderr 摘要，便于把真实原因透出到 UI。 */
public class GitCommandException extends RuntimeException {

    private final int exitCode;
    private final String stderr;

    public GitCommandException(String message, int exitCode, String stderr) {
        super(message + (stderr == null || stderr.isBlank() ? "" : "：" + stderr));
        this.exitCode = exitCode;
        this.stderr = stderr;
    }

    public GitCommandException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = -1;
        this.stderr = null;
    }

    public int exitCode() {
        return exitCode;
    }

    public String stderr() {
        return stderr;
    }
}
