// Tiny local multinomial Naive Bayes model. It learns only from this embedded
// starter corpus and user feedback saved on this computer; no text is uploaded.
const training = {
  study: [
    'calculus lecture algebra geometry physics chemistry biology statistics',
    'khan academy course lesson tutorial educational learning homework',
    'python programming coding software engineering github documentation',
    'study focus pomodoro concentration instrumental ambient study music',
    'notion task planning project notes productivity calendar',
    'google docs spreadsheet presentation research paper academic',
    'lecture university classroom exam revision flashcards language learning',
    'coursera udemy textbook audiobook skill training how to guide',
    'code editor terminal developer tools database design',
    'writing essay reading book science technology history'
  ],
  entertainment: [
    'celebrity gossip drama reality show influencer vlog',
    'football soccer basketball highlights sports match live game',
    'gaming gameplay stream twitch esports walkthrough',
    'comedy funny prank reaction meme entertainment',
    'movie trailer series episode horror thriller romance',
    'pop music album playlist concert lyrics dance',
    'shorts reels viral trending social media',
    'news debate talk show celebrity interview',
    'cartoon anime fan edit compilation',
    'travel food review unboxing lifestyle haul'
  ]
};
const tokenize = value => String(value || '').toLowerCase().match(/[a-z0-9]+/g) || [];

function train(extra = []) {
  const docs = [...training.study.map(text => [text, 'study']), ...training.entertainment.map(text => [text, 'entertainment']), ...extra];
  const vocab = new Set();
  const counts = { study: new Map(), entertainment: new Map() };
  const totals = { study: 0, entertainment: 0 };
  const docsPerClass = { study: 0, entertainment: 0 };
  for (const [text, label] of docs) {
    if (!counts[label]) continue;
    docsPerClass[label]++;
    for (const token of tokenize(text)) {
      vocab.add(token);
      counts[label].set(token, (counts[label].get(token) || 0) + 1);
      totals[label]++;
    }
  }
  return { vocab, counts, totals, docsPerClass, docCount: docs.length };
}
function classify(text, examples = []) {
  const model = train(examples);
  const tokens = tokenize(text);
  if (!tokens.length) return { category: 'unknown', confidence: 0, label: 'No media metadata' };
  const scores = {};
  for (const category of ['study', 'entertainment']) {
    scores[category] = Math.log((model.docsPerClass[category] + 1) / (model.docCount + 2));
    for (const token of tokens) scores[category] += Math.log(((model.counts[category].get(token) || 0) + 1) / (model.totals[category] + model.vocab.size));
  }
  const max = Math.max(scores.study, scores.entertainment);
  const expStudy = Math.exp(scores.study - max);
  const expEntertainment = Math.exp(scores.entertainment - max);
  const confidence = Math.max(expStudy, expEntertainment) / (expStudy + expEntertainment);
  const best = scores.study >= scores.entertainment ? 'study' : 'entertainment';
  return { category: confidence < 0.58 ? 'uncertain' : best, confidence, label: confidence < 0.58 ? 'Unclear media' : (best === 'study' ? 'Study / productivity' : 'Likely entertainment') };
}
module.exports = { classify };
