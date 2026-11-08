package moe.shizuku.manager.lspatch

import androidx.core.content.FileProvider

/**
 * aMiNo v2.x — authority carrier for the merged LSPatch manager's patched-apk provider.
 *
 * The vendored manager manifest declares this class at authority `${applicationId}.patched`
 * (see suite/lspatch/manager/src/main/AndroidManifest.xml). Upstream pointed the same node at
 * androidx.core.content.FileProvider directly; inside aMiNo that cannot merge, because aMiNo's
 * own FileProvider already occupies a second authority and the manifest merger only allows one
 * node per provider class. The behaviour is identical: a bare subclass with no added logic.
 */
class PatchedApkProvider : FileProvider()
