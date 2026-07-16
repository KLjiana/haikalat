package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Optional;

/** GLFW committed-char 之外的系统文本输入法适配边界。 */
public interface TextInputAdapter extends AutoCloseable {
    void activate(TextInputClient client);

    void deactivate(TextInputClient client);

    void setCandidateRect(TextInputRect rect);

    Optional<ImeComposition> composition();

    /** @return 当前平台是否提供 preedit/candidate 能力 */
    boolean compositionAvailable();

    @Override
    void close();
}
