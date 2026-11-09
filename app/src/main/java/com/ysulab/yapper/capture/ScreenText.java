package com.ysulab.yapper.capture;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Flattens the on-screen view tree (from {@code AssistStructure}) into plain text lines.
 *
 * <p>Order is the view-tree order, which is close to reading order. Repeated lines are kept
 * (an exam timetable repeats times), only a node's content description is dropped when it equals
 * its text, and immediate duplicates are collapsed. Pure Java via {@link Node}, so it is testable.
 */
public final class ScreenText {

    /** Minimal view of an {@code AssistStructure.ViewNode}. */
    public interface Node {
        CharSequence text();

        CharSequence contentDescription();

        boolean visible();

        int childCount();

        Node child(int index);
    }

    private ScreenText() {}

    public static String collect(List<? extends Node> roots, int maxChars) {
        StringBuilder out = new StringBuilder();
        String last = null;
        for (Node root : roots) {
            if (root == null) {
                continue;
            }
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                Node node = stack.pop();
                if (!node.visible()) {
                    continue;
                }
                String text = clean(node.text());
                String desc = clean(node.contentDescription());
                for (String line : new String[] {text, desc.equals(text) ? "" : desc}) {
                    if (line.isEmpty() || line.equals(last)) {
                        continue;
                    }
                    if (out.length() + line.length() + 1 > maxChars) {
                        return out.toString().trim();
                    }
                    out.append(line).append('\n');
                    last = line;
                }
                // Push children in reverse so they pop in order.
                for (int i = node.childCount() - 1; i >= 0; i--) {
                    Node child = node.child(i);
                    if (child != null) {
                        stack.push(child);
                    }
                }
            }
        }
        return out.toString().trim();
    }

    static String clean(CharSequence cs) {
        if (cs == null) {
            return "";
        }
        // Collapse whitespace runs inside a node but keep its own line breaks.
        String s = cs.toString().replace(' ', ' ').replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        s = s.replaceAll(" *\\n *", "\n").replaceAll("\\n{2,}", "\n");
        return s.trim();
    }
}
