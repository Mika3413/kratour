package com.kratour.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import com.kratour.core.Category
import com.kratour.core.ItemType

/** Règles du jeu, en quelques pages courtes. */
class HelpScreen(private val app: GameApp) : Screen {
    private val ui = app.ui
    private var page = 0
    private val prev = RectF()
    private val next = RectF()
    private val back = RectF()
    private val panel = RectF()

    private val pages = listOf(
        "Le but" to "Chaque équipe possède un fort entouré de briques. Ouvrez une brèche dans l'enceinte ennemie, puis faites entrer UNE de vos créatures dans le fort adverse : son équipe est immédiatement éliminée.\n\n" +
            "Vous commencez avec 3 Kratons nus, tous identiques (100 PV). Toutes les 60 s, une nouvelle créature nue arrive près de votre fort si vous en avez moins de 7 vivantes. Une créature morte reste morte.",
        "Les commandes" to "• Toucher une de vos créatures : la sélectionner (ou via la barre du bas).\n" +
            "• Toucher une case : elle s'y rend par le meilleur chemin.\n" +
            "• Toucher un ennemi : elle va le frapper (à portée si elle a une arme de tir).\n" +
            "• Toucher une brique ennemie : elle l'attaque.\n" +
            "• Glisser un doigt : déplacer la vue. Pincer : zoomer. Mini-carte : aller ailleurs.\n" +
            "• Une créature attaquée se défend toute seule. Un ordre de déplacement reste prioritaire : c'est ainsi qu'on bat en retraite.",
        "L'équipement" to "Toutes les 40 s, toutes les équipes reçoivent exactement le même choix entre deux objets (arme, puis défense, puis pouvoir...). Chaque équipe choisit de son côté ; l'objet va dans le sac commun.\n\n" +
            "Ouvrez le sac et touchez un objet pour le donner à la créature sélectionnée. Chaque créature porte au plus 1 arme, 1 défense et 1 pouvoir.\n\n" +
            "À sa mort, une créature lâche tout au sol : n'importe qui peut le ramasser en allant sur la case (on lâche alors l'objet du même type qu'on portait).",
        "Armes" to ItemType.ofCategory(Category.WEAPON).joinToString("\n") { "• ${it.label} : ${it.description}" } +
            "\n\nMains nues : on peut se battre, mais casser une brique prend une éternité. Le marteau et la bombe sont faits pour ça.",
        "Défenses" to ItemType.ofCategory(Category.DEFENSE).joinToString("\n") { "• ${it.label} : ${it.description}" } +
            "\n\nLe bouclier ne protège que de face : prenez vos ennemis à revers ! Kit et barricade s'utilisent depuis la fiche de la créature (bouton « Utiliser »).",
        "Pouvoirs" to ItemType.ofCategory(Category.POWER).joinToString("\n") { "• ${it.label} : ${it.description}" } +
            "\n\nUn pouvoir ne sert qu'une fois. Touchez l'emplacement « Pouvoir » de la fiche pour l'activer.\n\nSur la carte, quelques bonus neutres réapparaissent : potion, recharge de munitions, plume de hâte.",
        "Conseils" to "• Le premier sang compte : l'adversaire reste en infériorité numérique un moment, et son équipement est à ramasser.\n" +
            "• Ne laissez jamais votre fort sans surveillance : une seule créature qui entre suffit.\n" +
            "• Les ponts et chemins sont multiples : attaquez là où l'ennemi n'est pas, et faites diversion.\n" +
            "• Gardez vos blessés en retrait, soignez-les, et ramenez le butin.\n" +
            "• L'IA ne triche pas : aux niveaux élevés, elle gagne en jouant mieux, pas en voyant plus.",
    )

    override fun resize(w: Int, h: Int) {
        val u = ui.u
        panel.set(24 * u, 20 * u, w - 24 * u, h - 20 * u)
        back.set(panel.left + 12 * u, panel.bottom - 52 * u, panel.left + 132 * u, panel.bottom - 12 * u)
        next.set(panel.right - 132 * u, panel.bottom - 52 * u, panel.right - 12 * u, panel.bottom - 12 * u)
        prev.set(next.left - 128 * u, next.top, next.left - 8 * u, next.bottom)
    }

    override fun update(dt: Float) {}

    override fun draw(c: Canvas) {
        val u = ui.u
        c.drawColor(Color.rgb(26, 36, 56))
        ui.panel(c, panel)
        val (title, body) = pages[page]
        ui.text(c, title, panel.left + 24 * u, panel.top + 40 * u, 22f, Art.UI_ACCENT)
        ui.text(c, "${page + 1} / ${pages.size}", panel.right - 24 * u, panel.top + 36 * u, 12f, Art.UI_DIM, Paint.Align.RIGHT)
        ui.wrap(c, body, panel.left + 24 * u, panel.top + 72 * u, panel.width() - 48 * u, 12.5f, Art.UI_TEXT)
        ui.button(c, back, "Retour", size = 13f)
        ui.button(c, prev, "Précédent", enabled = page > 0, size = 13f)
        ui.button(c, next, if (page < pages.size - 1) "Suivant" else "Jouer !", accent = true, size = 13f)
    }

    override fun onTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_UP) return
        when {
            back.contains(e.x, e.y) -> app.showMenu()
            prev.contains(e.x, e.y) && page > 0 -> page--
            next.contains(e.x, e.y) -> if (page < pages.size - 1) page++ else app.showMenu()
        }
        app.sound.play(SoundFx.S.CLICK)
    }

    override fun onBack(): Boolean { app.showMenu(); return true }
}
