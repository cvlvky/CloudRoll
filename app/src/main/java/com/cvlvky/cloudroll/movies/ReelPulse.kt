package com.cvlvky.cloudroll.movies

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Hands a film over to ReelPulse (com.reelpulse.app), which opens its own title page for a shared
 * TMDB link: the diary, watchlist, reviews and OTT alerts live there.
 */
object ReelPulse {
    const val PACKAGE = "com.reelpulse.app"

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE) != null
    } catch (e: Exception) {
        false
    }

    fun tmdbLink(tmdbId: Int) = "https://www.themoviedb.org/movie/$tmdbId"

    /** Opens the film in ReelPulse, or its TMDB page when ReelPulse is not installed. */
    fun open(ctx: Context, tmdbId: Int) {
        val link = tmdbLink(tmdbId)
        if (installed(ctx)) {
            try {
                ctx.startActivity(
                    Intent(Intent.ACTION_SEND)
                        .setPackage(PACKAGE)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, link)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            } catch (e: ActivityNotFoundException) {
                // Falls through to the web page.
            }
        }
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Nothing can open it.
        }
    }

    /** Opens ReelPulse itself. */
    fun launch(ctx: Context) {
        ctx.packageManager.getLaunchIntentForPackage(PACKAGE)?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
