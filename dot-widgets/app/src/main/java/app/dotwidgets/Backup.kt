package app.dotwidgets

import android.appwidget.AppWidgetManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports every placed widget's settings and the weather place to JSON, and imports them back.
 * Widget ids change on a new phone or reinstall, so settings are matched by kind and order; any
 * left over are kept and applied to the next widgets of that kind that get added.
 */
object Backup {
    private const val FORMAT = "dot-widgets-backup"

    fun export(context: Context): String {
        val manager = AppWidgetManager.getInstance(context)
        val widgets = JSONArray()
        for (kind in WidgetKind.entries) {
            for (id in Scheduler.ids(context, manager, kind)) {
                widgets.put(JSONObject().put("kind", kind.name).put("config", JSONObject(WidgetStore.load(context, id, kind).toJson())))
            }
        }
        val root = JSONObject().put("format", FORMAT).put("version", 1).put("widgets", widgets)
        WeatherStore.place(context)?.let {
            root.put("weather", JSONObject().put("name", it.name).put("lat", it.latitude).put("lon", it.longitude))
        }
        return root.toString(2)
    }

    /** Returns how many widget settings were applied now (the rest wait for new widgets). */
    fun import(context: Context, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "No es una copia de Dot Widgets" }
        val byKind = mutableMapOf<WidgetKind, MutableList<String>>()
        val widgets = root.optJSONArray("widgets") ?: JSONArray()
        for (i in 0 until widgets.length()) {
            val entry = widgets.getJSONObject(i)
            val kind = WidgetKind.entries.firstOrNull { it.name == entry.optString("kind") } ?: continue
            byKind.getOrPut(kind) { mutableListOf() } += entry.getJSONObject("config").toString()
        }
        val manager = AppWidgetManager.getInstance(context)
        var applied = 0
        for ((kind, configs) in byKind) {
            val ids = Scheduler.ids(context, manager, kind)
            configs.forEachIndexed { i, config ->
                if (i < ids.size) {
                    WidgetStore.save(context, ids[i], WidgetConfig.fromJson(config, WidgetConfig.defaults(kind)))
                    applied++
                }
            }
            WidgetStore.setPending(context, kind, configs.drop(ids.size))
        }
        root.optJSONObject("weather")?.let {
            WeatherStore.setPlace(context, WeatherStore.Place(it.getString("name"), it.getDouble("lat"), it.getDouble("lon")))
            WeatherJob.runNow(context)
        }
        Scheduler.update(context)
        Scheduler.refresh(context)
        return applied
    }
}
