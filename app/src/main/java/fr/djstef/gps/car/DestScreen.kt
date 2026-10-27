package fr.djstef.gps.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import fr.djstef.gps.AppPrefs
import fr.djstef.gps.NavBridge
import fr.djstef.gps.Place

/** Liste de destinations : domicile, travail, récents. */
class DestScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val prefs = AppPrefs(carContext)
        val rows = ArrayList<Pair<String, Place>>()
        prefs.fav("home")?.let { rows.add("Domicile · ${it.name}" to it) }
        prefs.fav("work")?.let { rows.add("Travail · ${it.name}" to it) }
        for (r in prefs.recents()) {
            if (rows.size >= 6) break
            rows.add(r.name to r)
        }
        val list = ItemList.Builder()
        for ((title, place) in rows) {
            list.addItem(
                Row.Builder().setTitle(title).setOnClickListener {
                    NavBridge.go?.invoke(place)
                    screenManager.pop()
                }.build()
            )
        }
        if (rows.isEmpty()) list.setNoItemsMessage("Aucune destination : choisis-en une sur le téléphone")
        return ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle("Destinations")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
