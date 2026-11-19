package app.mettle

import android.app.Application
import app.mettle.data.db.MettleDatabase

class MettleApplication : Application() {
    val database: MettleDatabase by lazy { MettleDatabase.get(this) }
}
