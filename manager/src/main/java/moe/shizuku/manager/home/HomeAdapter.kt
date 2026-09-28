package moe.shizuku.manager.home

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool
import rikka.shizuku.Shizuku

class HomeAdapter(private val homeModel: HomeViewModel, private val appsModel: AppsViewModel, private val scope: CoroutineScope) :
    IdBasedRecyclerViewAdapter(ArrayList()) {

    init {
        updateData()
        setHasStableIds(true)
    }

    companion object {

        private const val ID_STATUS = 0L
        private const val ID_APPS = 1L
        private const val ID_TERMINAL = 2L
        private const val ID_START_ROOT = 3L
        private const val ID_START_WADB = 4L
        private const val ID_START_ADB = 5L
        private const val ID_LEARN_MORE = 6L
        private const val ID_ADB_PERMISSION_LIMITED = 7L
        private const val ID_AUTOMATION = 8L
        private const val ID_STEALTH = 9L
        private const val ID_SHELL = 10L
        private const val ID_ACCESSIBILITY = 11L
        private const val ID_CONNECTION = 12L
    }

    override fun onCreateCreatorPool(): IndexCreatorPool {
        return IndexCreatorPool()
    }

    fun updateData() {
        // aMiNo r1372: fall back to a default status instead of returning early.
        // The old early-return meant that when the service was NOT running the
        // adapter was never refreshed - live system state cards (accessibility,
        // connection states) stayed frozen on their old values even after the
        // user changed something in the system settings and came back.
        val status = homeModel.serviceStatus.value?.data ?: moe.shizuku.manager.model.ServiceStatus()
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0

        clear()
        addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)
        addItem(ConnectionStateViewHolder.CREATOR, null, ID_CONNECTION)
        addItem(ShellViewHolder.CREATOR, status, ID_SHELL)

        if (adbPermission) {
            addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS)
            addItem(TerminalViewHolder.CREATOR, status, ID_TERMINAL)
        }

        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
        }

        if (isPrimaryUser) {
            val rootRestart = running && status.uid == 0

            if (EnvironmentUtils.isRooted()) addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                EnvironmentUtils.isTelevision() ||
                EnvironmentUtils.getAdbTcpPort() > 0
            ) addItem(StartWirelessAdbViewHolder.creator(scope), null, ID_START_WADB)

            addItem(StartAdbViewHolder.CREATOR, null, ID_START_ADB)
        }
        addItem(AutomationViewHolder.CREATOR, null, ID_AUTOMATION)

        addItem(AccessibilityViewHolder.CREATOR, null, ID_ACCESSIBILITY)

        addItem(StealthViewHolder.CREATOR, null, ID_STEALTH)

        addItem(LearnMoreViewHolder.CREATOR, null, ID_LEARN_MORE)
        notifyDataSetChanged()
    }
}
