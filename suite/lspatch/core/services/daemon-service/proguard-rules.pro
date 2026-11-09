# aMiNo v2.x — consumer rules for the daemon IPC surface. The IVectorDaemon /
# IManagerService / IModuleService / IFrameworkService AIDL stubs and parcelables
# travel BY NAME across binder round-trips (parcel readParcelable resolves the
# class from its string name), and the daemon side may be unminified. Any future
# class placed in org.matrix.vector.ipc / org.matrix.vector.daemonservice must
# therefore keep its exact upstream name through the app's R8 run.
-keep class org.matrix.vector.ipc.** { *; }
-keep class org.matrix.vector.daemonservice.** { *; }
