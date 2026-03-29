package tgbotgpt.utils;

import java.util.ArrayList;

/**
 * A size-limited ArrayList that removes the oldest elements when the maximum capacity is exceeded.
 */
public class MessageLog<E> extends ArrayList<E> {
    private int maxSize;

    public MessageLog(int maxSize) {
        super();
        this.maxSize = maxSize;
    }

    @Override
    public boolean add(E e) {
        boolean added = super.add(e);
        if (size() > maxSize) {
            removeRange(0, size() - maxSize);
        }
        return added;
    }

    @Override
    public boolean addAll(int index, java.util.Collection<? extends E> c) {
        boolean added = super.addAll(index, c);
        if (size() > maxSize) {
            removeRange(0, size() - maxSize);
        }
        return added;
    }

}