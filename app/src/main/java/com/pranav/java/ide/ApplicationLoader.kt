package com.pranav.java.ide

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log

import com.pranav.lib_android.util.FileUtil

import kotlin.system.exitProcess

class ApplicationLoader: Application() {

    var uncaughtExceptionHandler: Thread.UncaughtExceptionHandler
	var mContext: Context

    override fun onCreate() {
		mContext = getApplicationContext()
		FileUtil.initializeContext(mContext)
        this.uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
		super.onCreate()
		Thread.setDefaultUncaughtExceptionHandler(object: Thread.UncaughtExceptionHandler() {
			fun uncaughtException(thread: Thread, throwable: Throwable) {
				val intent = Intent(getApplicationContext(), DebugActivity::class.java)
				intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
				intent.putExtra("error", Log.getStackTraceString(throwable))
				val pendingIntent = PendingIntent.getActivity(getApplicationContext(), 11111, intent, PendingIntent.FLAG_ONE_SHOT)

				val am: AlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE)
				am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, 1000, pendingIntent)

				Process.killProcess(Process.myPid())
				exitProcess(1)

				uncaughtExceptionHandler.uncaughtException(thread, throwable)
			}
		})
	}
	
	fun getContext(): Context = mContext
}
