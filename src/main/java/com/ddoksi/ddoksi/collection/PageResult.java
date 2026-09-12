package com.ddoksi.ddoksi.collection;

/**
 * 한 페이지 처리 결과.
 *
 * @param inserted      새로 저장된 법안
 * @param statusChanged 상태가 바뀌어 이력을 남긴 법안
 * @param unchanged     변화 없이 생존 시각만 갱신한 법안
 * @param skipped       필수 필드 누락 등으로 건너뛴 행
 */
public record PageResult(int inserted, int statusChanged, int unchanged, int skipped) {

    public int processed() {
        return inserted + statusChanged + unchanged;
    }

    public PageResult plus(PageResult other) {
        return new PageResult(
                inserted + other.inserted,
                statusChanged + other.statusChanged,
                unchanged + other.unchanged,
                skipped + other.skipped);
    }

    public static PageResult empty() {
        return new PageResult(0, 0, 0, 0);
    }
}
