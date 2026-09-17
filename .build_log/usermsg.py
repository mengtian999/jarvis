import io
P=r"D:\Code\IM\OpenMinis\OpenMinis-main\src\android\app\src\main\java\com\jarvis\app\ui\chat\ChatUserMessageUI.kt"
s=io.open(P,encoding="utf-8").read()

# 1) add selectMode param
old1="    onSelect: (() -> Unit)? = null,\n) {"
new1="    onSelect: (() -> Unit)? = null,\n    // [T-role-forward-multiselect] When true the row is in forward-select mode:\n    // suppress this bubble\u0027s own long-press menu so taps fall through to the\n    // row\u0027s tap-to-toggle handler.\n    selectMode: Boolean = false,\n) {"
assert s.count(old1)==1, "param anchor count=%d"%s.count(old1)
s=s.replace(old1,new1)

# 2) gate the pointerInput block (contains em-dashes)
old2="""                .pointerInput(message.id) {
                    detectTapGestures(
                        onLongPress = {
                            // Haptic must be fired by hand here. `combinedClickable`
                            // (what the tool pill uses) buzzes on long-press for
                            // free; raw `detectTapGestures` does not, so this
                            // gesture — chosen for its press OFFSET — silently
                            // lost the feedback every other long-press menu has.
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Anchor the menu to the bubble — DropdownMenu
                            // already places itself just below the anchor and
                            // auto-flips above when there\u0027s no room. Using
                            // the press y as offset (previous behavior) made
                            // the menu jump halfway down a tall bubble away
                            // from the user\u0027s finger, which on multi-line
                            // user messages landed in screen center.
                            showMenu = true
                        }
                    )
                }"""
new2="""                .then(
                    if (selectMode) {
                        // [T-role-forward-multiselect] In select mode the row\u0027s
                        // outer Box handles tap-to-toggle; suppress this bubble\u0027s
                        // own long-press menu so taps fall through to toggle.
                        Modifier
                    } else {
                        Modifier.pointerInput(message.id) {
                            detectTapGestures(
                                onLongPress = {
                                    // Haptic must be fired by hand here. `combinedClickable`
                                    // (what the tool pill uses) buzzes on long-press for
                                    // free; raw `detectTapGestures` does not, so this
                                    // gesture — chosen for its press OFFSET — silently
                                    // lost the feedback every other long-press menu has.
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // Anchor the menu to the bubble — DropdownMenu
                                    // already places itself just below the anchor and
                                    // auto-flips above when there\u0027s no room. Using
                                    // the press y as offset (previous behavior) made
                                    // the menu jump halfway down a tall bubble away
                                    // from the user\u0027s finger, which on multi-line
                                    // user messages landed in screen center.
                                    showMenu = true
                                }
                            )
                        }
                    }
                )"""
assert s.count(old2)==1, "pointerInput anchor count=%d"%s.count(old2)
s=s.replace(old2,new2)
io.open(P,"w",encoding="utf-8",newline="\n").write(s)
print("USERMSG DONE")
