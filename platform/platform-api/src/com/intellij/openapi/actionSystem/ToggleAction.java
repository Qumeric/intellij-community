// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.actionSystem;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * An action which has a selected state, and which toggles its selected state when performed.
 * Can be used to represent a menu item with a checkbox, or a toolbar button which keeps its pressed state.
 */
@SuppressWarnings("StaticInheritance")
public abstract class ToggleAction extends AnAction implements Toggleable {
  public ToggleAction(){
  }

  public ToggleAction(@Nullable final String text){
    super(text);
  }

  public ToggleAction(@Nullable final String text, @Nullable final String description, @Nullable final Icon icon) {
    super(text, description, icon);
  }

  @Override
  public final void actionPerformed(@NotNull final AnActionEvent e){
    final boolean state = !isSelected(e);
    setSelected(e, state);
    final Presentation presentation = e.getPresentation();
    Toggleable.setSelected(presentation, state);
  }

  /**
   * Returns the selected (checked, pressed) state of the action.
   * @param e the action event representing the place and context in which the selected state is queried.
   * @return true if the action is selected, false otherwise
   */
  public abstract boolean isSelected(@NotNull AnActionEvent e);

  /**
   * Sets the selected state of the action to the specified value.
   * @param e     the action event which caused the state change.
   * @param state the new selected state of the action.
   */
  public abstract void setSelected(@NotNull AnActionEvent e, boolean state);

  @Override
  public void update(@NotNull final AnActionEvent e){
    boolean selected = isSelected(e);
    final Presentation presentation = e.getPresentation();
    Toggleable.setSelected(presentation, selected);
    if (e.isFromContextMenu()) {
      //force to show check marks instead of toggled icons in context menu
      presentation.setIcon(null);
    }
  }
}
