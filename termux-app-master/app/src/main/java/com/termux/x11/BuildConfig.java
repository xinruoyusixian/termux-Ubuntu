package com.termux.x11;

/** 原 lorie 模块的 BuildConfig，并入 app 模块后手工保留（宿主固定为 com.termux）。 */
public final class BuildConfig {
    public static final String VERSION_NAME = "1.03.01-pad";
    public static final String COMMIT = "nongit";
    public static final String APPLICATION_ID = "com.termux";
    public static final boolean DEBUG = false;
    private BuildConfig() {}
}
