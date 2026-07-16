package com.kaleblangley.haikalat.subsystems.ui.text;

/** FreeType/HarfBuzz 可变对象的线程封闭守卫。 */
final class TextThreadOwner {
    private final Thread owner = Thread.currentThread();

    void check(String resource) {
        Thread current = Thread.currentThread();
        if (current != owner) {
            throw new IllegalStateException(resource + " belongs to thread '" + owner.getName()
                    + "' but was accessed from '" + current.getName() + "'");
        }
    }

    Thread thread() {
        return owner;
    }
}
