package org.lsposed.lspatch.share;

// aMiNo-v2: generated from share/java/src/template/java/org.lsposed.lspatch.share/LSPConfig.java
// with the OFFICIAL LSPatch v1.2 release values (verCode 487) and the pinned core (Vector)
// commit e00c5c5038bbcc14e0b382ab893301d6993e6989 (coreVerCode = commit count + 4200 = 7306,
// coreVerName = describe tag v2.2). Purely informational metadata embedded in patched configs.
public class LSPConfig {

    public static final LSPConfig instance;

    public int API_CODE;
    public int VERSION_CODE;
    public String VERSION_NAME;
    public int CORE_VERSION_CODE;
    public String CORE_VERSION_NAME;
    public String CORE_VERSION_HASH;

    private LSPConfig() {
    }

    static {
        instance = new LSPConfig();
        instance.API_CODE = 102;
        instance.VERSION_CODE = 487;
        instance.VERSION_NAME = "1.2";
        instance.CORE_VERSION_CODE = 7306;
        instance.CORE_VERSION_NAME = "2.2";
        instance.CORE_VERSION_HASH = "e00c5c5038bbcc14e0b382ab893301d6993e6989";
    }
}
