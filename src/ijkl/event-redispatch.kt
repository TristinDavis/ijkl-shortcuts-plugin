package ijkl

import com.intellij.ide.IdeEventQueue
import com.intellij.ide.IdeEventQueue.EventDispatcher
import com.intellij.openapi.application.Application
import com.intellij.openapi.ui.popup.IdePopupEventDispatcher
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.SystemInfo.isMac
import com.intellij.openapi.wm.IdeFocusManager
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Event.ALT_MASK
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.*
import java.util.stream.Stream
import javax.swing.JTree

fun initEventReDispatch(
    ideEventQueue: IdeEventQueue,
    keyboardFocusManager: KeyboardFocusManager,
    application: Application
) {
    val focusOwnerFinder = FocusOwnerFinder(keyboardFocusManager)
    val dispatcher = IjklEventDispatcher(focusOwnerFinder, ideEventQueue)

    // This is a workaround to make sure ijkl dispatch works in popups,
    // because IdeEventQueue invokes popup dispatchers before custom dispatchers.
    val popupEventDispatcher = IjklIdePopupEventDispatcher(dispatcher, focusOwnerFinder, afterDispatch = {
        ideEventQueue.popupManager.remove(it)
    })
    ideEventQueue.addActivityListener(Runnable {
        if (ideEventQueue.isPopupActive) {
            ideEventQueue.popupManager.push(popupEventDispatcher)
        }
    }, application)

    ideEventQueue.addDispatcher(dispatcher, application)
}

private class FocusOwnerFinder(private val keyboardFocusManager: KeyboardFocusManager) {
    fun find(): Component? = keyboardFocusManager.focusOwner ?: IdeFocusManager.findInstanceByContext(null).focusOwner
}

private class IjklIdePopupEventDispatcher(
    private val delegate: IjklEventDispatcher,
    private val focusOwnerFinder: FocusOwnerFinder,
    private val afterDispatch: (IjklIdePopupEventDispatcher) -> Unit
) : IdePopupEventDispatcher {

    override fun dispatch(event: AWTEvent): Boolean {
        val result = delegate.dispatch(event)
        afterDispatch(this)
        return result
    }
    override fun getComponent() = focusOwnerFinder.find()
    override fun getPopupStream(): Stream<JBPopup> = Stream.empty()
    override fun requestFocus() = false
    override fun close() = false
    override fun setRestoreFocusSilentely() {}
}

private class IjklEventDispatcher(
    private val focusOwnerFinder: FocusOwnerFinder,
    private val ideEventQueue: IdeEventQueue
) : EventDispatcher {

    override fun dispatch(event: AWTEvent): Boolean {
        if (event !is KeyEvent) return false
        if (event.modifiers.and(ALT_MASK) == 0) return false

        val newEvent = event.mapIfIjkl()

        return if (newEvent != null) {
            ideEventQueue.dispatchEvent(newEvent)
            true
        } else {
            false
        }
    }

    @Suppress("NOTHING_TO_INLINE")
    private inline fun KeyEvent.mapIfIjkl(): KeyEvent? {
        // For performance optimisation reasons do the cheapest checks first, i.e. key code, popup, focus in tree.
        // There is no empirical evidence that these optimisations are actually useful though.
        val isIjkl =
            keyCode == VK_I || keyCode == VK_J ||
            keyCode == VK_K || keyCode == VK_L ||
            keyCode == VK_F || keyCode == VK_W ||
            keyCode == VK_U || keyCode == VK_O ||
            keyCode == VK_M || keyCode == VK_N ||
            keyCode == VK_SEMICOLON
        if (!isIjkl) return null

        val component = focusOwnerFinder.find()
        val hasParentTree = component.hasParentTree()
        if (!ideEventQueue.isPopupActive && !hasParentTree && !component.hasCommitDialogParent()) return null

        val useCommitDialogWorkarounds = component.hasCommitDialogParent() && !hasParentTree
        if (useCommitDialogWorkarounds) {
            when (keyCode) {
                VK_I -> return null // No mapping so that alt+i triggers "Commit" action
                VK_K -> return null // No mapping for the symmetry with VK_I.
                VK_J -> return copyWithModifier(VK_LEFT) // Override for the symmetry with the VK_L.
                VK_L -> return copyWithModifier(VK_RIGHT) // Override mnemonic for "Clean code" (assuming move left is used more often).
                VK_N -> return copyWithoutAlt(VK_LEFT) // Override for the symmetry with the VK_M.
                VK_M -> return copyWithoutAlt(VK_RIGHT) // Override mnemonic for "Amend commit" (assuming that commits are not amended very often).
            }
        }

        if (hasParentTree) {
            when (keyCode) {
                VK_J -> return copyWithoutAlt(VK_LEFT) // Convert to "left" rather than "alt left" so that in trees it works as "collapse node".
                VK_L -> return copyWithoutAlt(VK_RIGHT) // Convert to "right" rather than "alt right" so that in trees it works as "expand node".
            }
        }

        if (ideEventQueue.isPopupActive) {
            when (keyCode) {
                VK_N -> return copyWithoutAlt(VK_LEFT)
                VK_M -> return copyWithoutAlt(VK_RIGHT)
                VK_SEMICOLON -> return copyWithoutAlt(VK_DELETE)
            }
        }

        return when (keyCode) {
            VK_I -> copyWithoutAlt(VK_UP)
            VK_K -> copyWithoutAlt(VK_DOWN)
            VK_F -> copyWithoutAlt(VK_PAGE_DOWN)
            VK_W -> copyWithoutAlt(VK_PAGE_UP)
            VK_U -> copyWithoutAlt(VK_HOME)
            VK_O -> copyWithoutAlt(VK_END)
            else -> null
        }
    }
}

// Using zero char because:
//  - if it's original letter, then navigation doesn't work in popups
//  - if it's some other letter, then in Navigate to File/Class the letter is inserted into text area
private const val zeroChar = 0.toChar()

private fun KeyEvent.copyWithoutAlt(keyCode: Int) =
    KeyEvent(
        source as Component,
        id,
        `when`,
        modifiers.and(ALT_MASK.inv()),
        keyCode,
        zeroChar
    )


private fun KeyEvent.copyWithModifier(keyCode: Int) =
    KeyEvent(
        source as Component,
        id,
        `when`,
        modifiers.or(if (isMac) ALT_MASK else CTRL_MASK),
        keyCode,
        zeroChar
    )

private fun Component?.hasParentTree(): Boolean = when {
    this == null -> false
    this is JTree -> true
    else -> parent.hasParentTree()
}

private fun Component?.hasCommitDialogParent(): Boolean = when {
    this == null -> false
    this.toString().contains("layout=com.intellij.openapi.vcs.changes.ui.CommitChangeListDialog") -> true
    else -> parent.hasCommitDialogParent()
}
