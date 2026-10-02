package com.kratour.core

/** Les trois emplacements d'équipement d'une créature. */
enum class Category(val label: String) {
    WEAPON("Arme"),
    DEFENSE("Défense"),
    POWER("Pouvoir");

    fun next(): Category = values()[(ordinal + 1) % values().size]
}

/**
 * Caractéristiques d'une arme (ou des mains nues).
 *
 * @param damage dégâts par coup contre une créature
 * @param cooldown secondes entre deux attaques
 * @param range portée (distance de Chebyshev pour la mêlée, euclidienne pour le tir)
 * @param structureMult multiplicateur des dégâts contre briques et barricades
 * @param ranged attaque à distance (projectile)
 * @param knockback repousse la cible d'une case
 * @param splash rayon de l'explosion (bombe)
 */
class WeaponStats(
    val damage: Float,
    val cooldown: Float,
    val range: Float,
    val structureMult: Float,
    val ranged: Boolean = false,
    val knockback: Boolean = false,
    val splash: Float = 0f,
    val structureSplashDamage: Float = 0f,
)

enum class ItemType(
    val category: Category,
    val label: String,
    val description: String,
    /** Nombre de charges initiales (munitions, durabilité, utilisations). 0 = illimité. */
    val maxCharges: Int,
    val weapon: WeaponStats? = null,
) {
    // ---------------------------------------------------------------- Armes
    SWORD(
        Category.WEAPON, "Épée", "Rapide et polyvalente. Dégâts moyens.", 0,
        WeaponStats(damage = 11f, cooldown = 0.8f, range = 1f, structureMult = 0.25f),
    ),
    MACE(
        Category.WEAPON, "Masse", "Lente, frappe très fort et repousse la cible.", 0,
        WeaponStats(damage = 26f, cooldown = 1.75f, range = 1f, structureMult = 0.6f, knockback = true),
    ),
    SPEAR(
        Category.WEAPON, "Lance", "Frappe à 2 cases : touche avant d'être touchée.", 0,
        WeaponStats(damage = 10f, cooldown = 1.0f, range = 2f, structureMult = 0.2f),
    ),
    HAMMER(
        Category.WEAPON, "Marteau", "Démolisseur : pulvérise briques et barricades.", 0,
        WeaponStats(damage = 8f, cooldown = 1.1f, range = 1f, structureMult = 3.9f),
    ),
    CROSSBOW(
        Category.WEAPON, "Arbalète", "Tire de loin. 6 carreaux.", 6,
        WeaponStats(damage = 14f, cooldown = 1.4f, range = 6f, structureMult = 0.1f, ranged = true),
    ),
    BOMB(
        Category.WEAPON, "Bombe", "2 bombes. Explosion de zone, ravage les briques.", 2,
        WeaponStats(
            damage = 32f, cooldown = 1.5f, range = 4f, structureMult = 0f, ranged = true,
            splash = 1.5f, structureSplashDamage = 300f,
        ),
    ),

    // ---------------------------------------------------------------- Défenses
    SHIELD(Category.DEFENSE, "Bouclier", "Bloque totalement 6 coups reçus de face.", 6),
    ARMOR(Category.DEFENSE, "Armure", "Réduit les dégâts de 45 % jusqu'à usure.", 110),
    BUBBLE(Category.DEFENSE, "Bulle", "Absorbe complètement 2 attaques.", 2),
    REPAIR_KIT(Category.DEFENSE, "Kit de réparation", "Reconstruit 3 briques de votre fort.", 3),
    BARRICADE(Category.DEFENSE, "Barricade", "Pose 2 obstacles temporaires.", 2),

    // ---------------------------------------------------------------- Pouvoirs
    HEAL(Category.POWER, "Soin", "Rend 50 PV immédiatement.", 1),
    SPEED(Category.POWER, "Vitesse", "Déplacement +60 % pendant 12 s.", 1),
    FORCE(Category.POWER, "Force", "Dégâts +50 % pendant 12 s.", 1),
    SHOCKWAVE(Category.POWER, "Onde de choc", "Repousse et étourdit les ennemis proches.", 1),
    FREEZE(Category.POWER, "Gel", "Immobilise un ennemi 3,5 s (portée 5).", 1),
    INVISIBILITY(Category.POWER, "Invisibilité", "Invisible 10 s. Attaquer l'annule.", 1);

    val usesCharges: Boolean get() = maxCharges > 0

    companion object {
        fun ofCategory(c: Category): List<ItemType> = values().filter { it.category == c }
    }
}

/** Instance d'objet : un type et ses charges restantes. */
class Item(val type: ItemType, var charges: Int = type.maxCharges) {
    val category: Category get() = type.category
    override fun toString(): String = if (type.usesCharges) "${type.label}($charges)" else type.label
}

/** Objet posé au sol, récupérable par n'importe quelle créature. */
class GroundItem(val id: Int, val item: Item, val x: Int, val y: Int)

/** Bonus neutres simples présents sur la carte (réapparaissent). */
enum class BonusType(val label: String) {
    POTION("Petite potion"),
    RECHARGE("Recharge"),
    HASTE("Plume de hâte"),
}

class BonusSpot(val x: Int, val y: Int, val type: BonusType) {
    var available = true
    var respawnTimer = 0f
}
