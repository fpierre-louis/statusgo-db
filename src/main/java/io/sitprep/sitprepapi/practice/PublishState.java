package io.sitprep.sitprepapi.practice;

/**
 * Content lifecycle: {@code DRAFT -> SAFETY_REVIEWED -> PUBLISHED -> RETIRED}.
 *
 * <p>The state lives in the content file and changes by commit, so the
 * sequence is enforced as "what each state requires" in
 * {@link PracticeContentValidator}: anything past DRAFT needs an approved
 * safety review pinned to the content hash; PUBLISHED needs a publish date;
 * RETIRED needs a retire date.</p>
 */
public enum PublishState {
    DRAFT,
    SAFETY_REVIEWED,
    PUBLISHED,
    RETIRED;

    /** Past DRAFT: a safety reviewer has signed off on this exact content. */
    public boolean requiresReview() {
        return this != DRAFT;
    }

    /** May a NEW run start on this version in production? */
    public boolean startableInProduction() {
        return this == PUBLISHED;
    }
}
