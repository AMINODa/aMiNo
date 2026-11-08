package moe.shizuku.manager.lspatch

import android.content.Intent
import android.os.Bundle
import moe.shizuku.manager.app.AppActivity
import org.lsposed.lspatch.ui.activity.MainActivity

/**
 * aMiNo v2.x — LSPatch ENTRY (drawer target). Field truth (user, verbatim):
 * «لا اريد ضهور الايقونات منفصلة عن amino … اريدهم مدموجيم في تطبيق امينو … بدون اتلاف الادوار او
 * الواجهات فهيا جيدة كما هيا» and earlier «اريدها كما في النسخة الاصلية غير فقط اللون».
 *
 * The original LSPatch manager (org.lsposed.lspatch, every screen/setting/option upstream, aMiNo
 * colours only) is no longer a companion APK on the home screen — it is compiled INTO this very
 * app (see suite/lspatch/manager). So this activity's whole job is one hop: open the manager's
 * own MainActivity in-process and disappear. No install step exists any more, because there is
 * nothing left to install.
 */
class LspatchEntryActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
