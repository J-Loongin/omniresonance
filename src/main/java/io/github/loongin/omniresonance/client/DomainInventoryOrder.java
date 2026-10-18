// SPDX-License-Identifier: LGPL-3.0-or-later
package io.github.loongin.omniresonance.client;

import io.github.loongin.omniresonance.transfer.ResourceVariantKey;
import java.util.AbstractList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongBinaryOperator;
import org.jetbrains.annotations.Nullable;

/**
 * Client-view-owned ranked AVL sequence. Identity maps retain exactly the visible entries, including Shift
 * placeholders; replacing the view or closing drops the entire index. Rank selection, identity position,
 * insertion and deletion take O(log N) without rebuilding suffix positions. No world or quantity ownership.
 */
final class DomainInventoryOrder extends AbstractList<Long> {
    private @Nullable Node root;
    private final Map<ResourceVariantKey, Node> identities = new HashMap<>();
    private final Map<Long, ResourceVariantKey> keys = new HashMap<>();

    private static final class Node {
        final ResourceVariantKey key;
        long id;
        int height = 1, count = 1;
        @Nullable
        Node left, right, parent;

        Node(long id, ResourceVariantKey key) {
            this.id = id;
            this.key = key;
        }
    }

    @Override
    public int size() {
        return count(root);
    }

    @Override
    public Long get(int index) {
        return at(index).id;
    }

    @Nullable
    ResourceVariantKey key(long id) {
        return keys.get(id);
    }

    int height() {
        return height(root);
    }

    int position(ResourceVariantKey key) {
        Node node = identities.get(key);
        if (node == null) return -1;
        int position = count(node.left);
        while (node.parent != null) {
            Node parent = node.parent;
            if (node == parent.right) position += count(parent.left) + 1;
            node = parent;
        }
        return position;
    }

    int insertionPoint(long id, LongBinaryOperator compare) {
        int index = 0;
        Node node = root;
        while (node != null) {
            if (compare.applyAsLong(node.id, id) < 0) {
                index += count(node.left) + 1;
                node = node.right;
            } else node = node.left;
        }
        return index;
    }

    void insert(int index, long id, ResourceVariantKey key) {
        if (index < 0 || index > size()) throw new IndexOutOfBoundsException(index);
        Objects.requireNonNull(key);
        if (identities.containsKey(key) || keys.containsKey(id))
            throw new IllegalArgumentException("Duplicate identity");
        Node node = new Node(id, key);
        root = insert(root, index, node);
        root.parent = null;
        identities.put(key, node);
        keys.put(id, key);
        modCount++;
    }

    void replaceId(ResourceVariantKey key, long id) {
        Node node = Objects.requireNonNull(identities.get(key));
        if (node.id == id) return;
        if (keys.containsKey(id)) throw new IllegalArgumentException("Duplicate result ID");
        keys.remove(node.id);
        node.id = id;
        keys.put(id, key);
    }

    @Override
    public Long remove(int index) {
        Node removed = at(index);
        root = remove(root, index);
        if (root != null) root.parent = null;
        identities.remove(removed.key);
        keys.remove(removed.id);
        modCount++;
        return removed.id;
    }

    private Node at(int index) {
        Objects.checkIndex(index, size());
        Node node = root;
        while (true) {
            int left = count(node.left);
            if (index == left) return node;
            if (index < left) node = node.left;
            else {
                index -= left + 1;
                node = node.right;
            }
        }
    }

    private static Node insert(@Nullable Node tree, int index, Node node) {
        if (tree == null) return node;
        int left = count(tree.left);
        if (index <= left) left(tree, insert(tree.left, index, node));
        else right(tree, insert(tree.right, index - left - 1, node));
        return balance(tree);
    }

    private static @Nullable Node remove(Node tree, int index) {
        int left = count(tree.left);
        if (index < left) left(tree, remove(tree.left, index));
        else if (index > left) right(tree, remove(tree.right, index - left - 1));
        else {
            if (tree.left == null) return tree.right;
            if (tree.right == null) return tree.left;
            Node successor = tree.right;
            while (successor.left != null) successor = successor.left;
            Node remaining = remove(tree.right, 0);
            left(successor, tree.left);
            right(successor, remaining);
            successor.parent = tree.parent;
            return balance(successor);
        }
        return balance(tree);
    }

    private static void left(Node node, @Nullable Node child) {
        node.left = child;
        if (child != null) child.parent = node;
    }

    private static void right(Node node, @Nullable Node child) {
        node.right = child;
        if (child != null) child.parent = node;
    }

    private static int count(@Nullable Node node) {
        return node == null ? 0 : node.count;
    }

    private static int height(@Nullable Node node) {
        return node == null ? 0 : node.height;
    }

    private static void update(Node node) {
        node.count = 1 + count(node.left) + count(node.right);
        node.height = 1 + Math.max(height(node.left), height(node.right));
    }

    private static Node balance(Node node) {
        update(node);
        if (height(node.left) - height(node.right) > 1) {
            if (height(node.left.left) < height(node.left.right)) left(node, rotateLeft(node.left));
            return rotateRight(node);
        }
        if (height(node.right) - height(node.left) > 1) {
            if (height(node.right.right) < height(node.right.left)) right(node, rotateRight(node.right));
            return rotateLeft(node);
        }
        return node;
    }

    private static Node rotateLeft(Node node) {
        Node next = node.right;
        next.parent = node.parent;
        right(node, next.left);
        left(next, node);
        update(node);
        update(next);
        return next;
    }

    private static Node rotateRight(Node node) {
        Node next = node.left;
        next.parent = node.parent;
        left(node, next.right);
        right(next, node);
        update(node);
        update(next);
        return next;
    }
}
