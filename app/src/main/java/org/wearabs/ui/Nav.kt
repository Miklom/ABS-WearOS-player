package org.wearabs.ui

/** Login, home, and the three content screens. Arguments ride in the route. */
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SEARCH = "search"
    const val BOOK = "book/{itemId}"
    const val PLAYER = "player/{itemId}"

    fun book(itemId: String) = "book/$itemId"
    fun player(itemId: String) = "player/$itemId"
}
