export const CHAPTER_THRESHOLDS = [0, 100, 250, 500, 900, 1500, 2500, 4000];

export function chapterForPoints(total) {
  return CHAPTER_THRESHOLDS.filter((threshold) => Number(total) >= threshold).length;
}

// Mission rewards start at half their catalog value, then taper by 3% per chapter.
export function adjustedReward(basePoints, total) {
  const chapter = chapterForPoints(total);
  const reduction = Math.max(0, chapter - 1) * 0.03;
  return Math.max(1, Math.floor(Number(basePoints) * 0.5 * (1 - reduction)));
}
