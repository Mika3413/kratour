package com.kratour.core

/** Valeurs d'équilibrage centralisées. Toutes les équipes utilisent exactement les mêmes. */
object Balance {
    /** Pas de simulation fixe (secondes). */
    const val TICK = 0.05f

    // Créatures
    const val MAX_HP = 100f
    const val START_CREATURES = 3
    const val MAX_ALIVE = 7
    const val SPAWN_INTERVAL = 60f

    /** Temps pour traverser une case orthogonale (les diagonales coûtent √2). */
    const val STEP_TIME = 0.7f

    // Mains nues
    val FISTS = WeaponStats(damage = 5f, cooldown = 1.0f, range = 1f, structureMult = 0.08f)

    // Choix d'équipement
    const val FIRST_OFFER_AT = 20f
    const val OFFER_INTERVAL = 40f

    // Briques / fort
    const val BRICK_HP = 900f
    const val BARRICADE_HP = 150f
    const val BARRICADE_DURATION = 40f
    const val REPAIR_TIME = 1.2f

    // Défenses
    const val ARMOR_REDUCTION = 0.45f
    const val SHIELD_FRONT_DOT = 0.2f

    // Pouvoirs
    const val HEAL_AMOUNT = 50f
    const val SPEED_DURATION = 12f
    const val SPEED_MULT = 1.6f
    const val FORCE_DURATION = 12f
    const val FORCE_MULT = 1.5f
    const val INVIS_DURATION = 10f
    const val FREEZE_DURATION = 3.5f
    const val FREEZE_RANGE = 5f
    const val SHOCK_RADIUS = 2.3f
    const val SHOCK_PUSH = 2
    const val SHOCK_DAMAGE = 8f
    const val SHOCK_STUN = 0.8f

    // Bonus neutres
    const val BONUS_RESPAWN = 45f
    const val POTION_HEAL = 25f
    const val HASTE_DURATION = 6f
    const val RECHARGE_BOLTS = 3

    // Comportement automatique
    const val GUARD_LEASH = 3.5f
    const val KNOCKBACK_STUN = 0.35f
    const val PROJECTILE_SPEED = 9f // cases / seconde
    const val BOMB_FLIGHT = 0.8f // secondes
}
