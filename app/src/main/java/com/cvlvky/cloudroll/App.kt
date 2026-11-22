package com.cvlvky.cloudroll

import android.app.Application
import com.cvlvky.cloudroll.backup.BackupScheduler
import com.cvlvky.cloudroll.data.Db
import com.cvlvky.cloudroll.data.Prefs
import com.cvlvky.cloudroll.tg.Tg
import com.cvlvky.cloudroll.util.Notify

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Db.init(this)
        Notify.createChannels(this)
        Tg.init(this)
        BackupScheduler.update(this)
    }
}
