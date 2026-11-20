package app.mettle

import android.app.Application
import app.mettle.data.db.MettleDatabase

class MettleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        app.mettle.data.remote.Backend.init(this)
        app.mettle.update.UpdateWorker.schedule(this)
    }

    val database: MettleDatabase by lazy { MettleDatabase.get(this) }
}
