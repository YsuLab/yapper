package com.ysulab.yapper.assist;

import android.app.assist.AssistStructure;
import android.view.View;
import com.ysulab.yapper.capture.ScreenText;
import java.util.ArrayList;
import java.util.List;

/** Adapts {@link AssistStructure} to the pure-Java {@link ScreenText.Node}. */
final class AssistNodes {

    private AssistNodes() {}

    static List<ScreenText.Node> roots(AssistStructure structure) {
        List<ScreenText.Node> out = new ArrayList<>();
        if (structure == null) {
            return out;
        }
        for (int i = 0; i < structure.getWindowNodeCount(); i++) {
            AssistStructure.ViewNode root = structure.getWindowNodeAt(i).getRootViewNode();
            if (root != null) {
                out.add(new Adapter(root));
            }
        }
        return out;
    }

    private static final class Adapter implements ScreenText.Node {
        private final AssistStructure.ViewNode node;

        Adapter(AssistStructure.ViewNode node) {
            this.node = node;
        }

        @Override
        public CharSequence text() {
            return node.getText();
        }

        @Override
        public CharSequence contentDescription() {
            return node.getContentDescription();
        }

        @Override
        public boolean visible() {
            return node.getVisibility() == View.VISIBLE;
        }

        @Override
        public int childCount() {
            return node.getChildCount();
        }

        @Override
        public ScreenText.Node child(int index) {
            AssistStructure.ViewNode c = node.getChildAt(index);
            return c == null ? null : new Adapter(c);
        }
    }
}
