package cn.iocoder.yudao.module.infra.zhongshu.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationImageOptionsTest {

    @Test
    void missingValuesUseStageDefaults() {
        assertThat(GenerationImageOptions.normalize("FLAT", null, null))
                .isEqualTo(new GenerationImageOptions("2K", "PORTRAIT"));
        assertThat(GenerationImageOptions.normalize("ELEVATION", "", ""))
                .isEqualTo(new GenerationImageOptions("2K", "LANDSCAPE"));
    }

    @Test
    void valuesAreCanonicalized() {
        assertThat(GenerationImageOptions.normalize("FLAT", "4k", "landscape"))
                .isEqualTo(new GenerationImageOptions("4K", "LANDSCAPE"));
    }

    @Test
    void unsupportedValuesAreRejected() {
        assertThatThrownBy(() -> GenerationImageOptions.normalize("FLAT", "8K", "PORTRAIT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GenerationImageOptions.normalize("FLAT", "2K", "SQUARE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GenerationImageOptions.normalize("OTHER", "2K", "PORTRAIT"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
