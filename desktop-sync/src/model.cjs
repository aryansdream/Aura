// Small, on-device TF-IDF logistic classifier for media metadata and OCR text.
// It is intentionally conservative: only clear predictions can trigger a pause.
const starter = {
  study: [
    'Calculus lecture limits derivatives integration examples Khan Academy',
    'Physics class mechanics electricity waves solved problems',
    'Organic chemistry lecture reaction mechanisms university course',
    'Biology lesson cell division genetics and exam revision',
    'Statistics tutorial probability regression worked examples',
    'How to solve linear algebra matrix eigenvalue problems lesson',
    'Python programming tutorial functions loops debugging code',
    'JavaScript documentation API reference web development',
    'Git version control branching pull request developer workflow',
    'Linux terminal command line programming project setup',
    'Data structures algorithms lecture binary trees graph search',
    'Machine learning course neural networks model training lesson',
    'Database SQL joins normalization query design tutorial',
    'Excel spreadsheet formulas pivot tables data analysis training',
    'Academic research paper literature review university seminar',
    'Classroom lecture notes assignment deadline course module',
    'How to write an essay thesis citations academic writing',
    'Language learning grammar vocabulary pronunciation lesson',
    'Read chapter textbook history of science study guide',
    'Exam preparation flashcards revision questions and answers',
    'Project management task planning calendar meeting notes',
    'Google Docs collaborative report draft and presentation',
    'Design software CAD technical drawing professional training',
    'Financial accounting practice problems balance sheet lesson',
    'Medical anatomy course lecture bones muscles physiology',
    'Engineering electronics circuit analysis lab lecture',
    'Productivity tutorial use keyboard shortcuts workflow',
    'Meditation breathing timer mindful focus exercise',
    'Instrumental lo fi study playlist no lyrics concentration',
    'Classical piano background music for reading and studying',
    'Ambient rain sounds focus session work music',
    'Pomodoro study session deep work concentration timer',
    'Coursera lecture certificate course lesson module',
    'Udemy software development course lesson exercise',
    'Textbook audiobook educational nonfiction learning chapter',
    'Technical documentation installation guide troubleshooting',
    'Tutorial solve differential equations step by step',
    'Geography lesson climate maps and population data',
    'Physics experiment lab demonstration educational lecture',
    'Research methods how to cite sources APA style',
    'Presentation on sustainable energy for university class',
    'Professional skills training workplace communication course',
    'Typing practice coding exercise and programming challenge',
    'Study with me quiet library pomodoro session',
    'Learn spreadsheet charts data visualization tutorial',
    'Cybersecurity course network protocols and encryption lesson',
    'Statistics homework confidence intervals regression examples',
    'Daily planner organize study schedule and assignments',
    'Educational documentary history lecture classroom',
    'Read along literature analysis book discussion for class'
  ],
  entertainment: [
    'Celebrity gossip red carpet relationship drama interview',
    'Reality TV episode reunion argument and surprise elimination',
    'Funny prank compilation reactions and viral moments',
    'Stand up comedy full set jokes live audience',
    'Sketch comedy parody sitcom funny scenes',
    'Movie trailer action adventure official teaser',
    'New season episode recap thriller series streaming',
    'Horror movie clips scary scenes and jump scares',
    'Romantic comedy film best moments movie clips',
    'Gaming livestream Minecraft survival gameplay episode',
    'Fortnite ranked match highlights esports tournament',
    'Video game walkthrough boss fight playthrough',
    'Twitch streamer reacts to funny videos live',
    'Football match highlights goals and best plays',
    'Basketball game dunk compilation sports highlights',
    'Formula racing grand prix race recap',
    'Pop music official music video new single',
    'Rap song lyrics video album release',
    'Live concert performance crowd singing artist',
    'Top chart hits dance music playlist',
    'Singer interview tour announcement fan questions',
    'Dance challenge choreography viral social media trend',
    'YouTube Shorts funny animal clips compilation',
    'Instagram Reels creator comedy skit trending',
    'TikTok viral challenge reaction and memes',
    'Food vlog street food tasting spicy noodles review',
    'Travel vlog hotel tour vacation beach day',
    'Unboxing new phone gadgets first impressions',
    'Makeup tutorial beauty routine product haul',
    'Lifestyle influencer daily routine apartment tour',
    'Car review test drive luxury vehicle reactions',
    'Celebrity interview talk show funny moments',
    'Anime episode fight scene fan edit compilation',
    'Cartoon funny clips and animated movie scenes',
    'Reality competition show dramatic finale',
    'Practical jokes hidden camera prank reactions',
    'Top ten movie scenes action movie compilation',
    'Stand up comic hilarious crowd work clip',
    'Music playlist pop hits lyrics and dance tracks',
    'Guitar solo live performance rock concert',
    'Sports commentary championship final reaction',
    'Unusual facts reaction video entertaining stories',
    'Comedy podcast celebrity gossip discussion',
    'Daily vlog shopping haul and restaurant review',
    'Movie review spoiler discussion favorite scenes',
    'Gaming funny moments fails and rage compilation',
    'Karaoke song performance music video',
    'Viral animal videos cute puppies funny cats',
    'Reality show contestants argue during dramatic episode',
    'Comedian jokes parody celebrity impressions'
  ]
};

const stopWords = new Set(['a', 'an', 'and', 'are', 'as', 'at', 'by', 'for', 'from', 'in', 'into', 'is', 'of', 'on', 'or', 'the', 'to', 'with']);
const tokenize = value => String(value || '').normalize('NFKC').toLowerCase().match(/[\p{L}\p{N}]+/gu) || [];
const sigmoid = value => value < -30 ? 0 : value > 30 ? 1 : 1 / (1 + Math.exp(-value));
let cachedKey = '';
let cachedModel = null;

function features(text) {
  const words = tokenize(text).filter(word => !stopWords.has(word));
  const counts = new Map();
  for (let i = 0; i < words.length; i++) {
    const word = words[i];
    counts.set(`w:${word}`, (counts.get(`w:${word}`) || 0) + 1);
    if (i) counts.set(`b:${words[i - 1]}_${word}`, (counts.get(`b:${words[i - 1]}_${word}`) || 0) + 1);
  }
  return { words, counts };
}

function train(extra) {
  const docs = [];
  for (const category of ['study', 'entertainment']) {
    for (const text of starter[category]) docs.push({ text, y: category === 'entertainment' ? 1 : 0, weight: 1 });
  }
  for (const item of extra) {
    if (!Array.isArray(item) || item.length < 2) continue;
    const text = String(item[0] || '').slice(0, 500);
    const label = item[1] === 'study' ? 0 : item[1] === 'entertainment' ? 1 : -1;
    if (!text.trim() || label < 0) continue;
    docs.push({ text, y: label, weight: 3 });
  }
  const vectors = docs.map(doc => ({ ...doc, ...features(doc.text) }));
  const df = new Map();
  for (const vector of vectors) for (const key of vector.counts.keys()) df.set(key, (df.get(key) || 0) + 1);
  const idf = new Map([...df].map(([key, count]) => [key, Math.log(1 + (vectors.length - count + 0.5) / (count + 0.5))]));
  for (const vector of vectors) {
    let norm = 0;
    vector.values = new Map();
    for (const [key, count] of vector.counts) {
      const value = (1 + Math.log(count)) * (idf.get(key) || 0);
      vector.values.set(key, value);
      norm += value * value;
    }
    const scale = norm ? 1 / Math.sqrt(norm) : 1;
    for (const [key, value] of vector.values) vector.values.set(key, value * scale);
  }
  const weights = new Map();
  let bias = 0;
  const learningRate = 0.28;
  const regularization = 0.0015;
  for (let epoch = 0; epoch < 180; epoch++) {
    const gradients = new Map();
    let biasGradient = 0;
    let weightTotal = 0;
    for (const sample of vectors) {
      let score = bias;
      for (const [key, value] of sample.values) score += (weights.get(key) || 0) * value;
      const error = (sigmoid(score) - sample.y) * sample.weight;
      biasGradient += error;
      weightTotal += sample.weight;
      for (const [key, value] of sample.values) gradients.set(key, (gradients.get(key) || 0) + error * value);
    }
    const denominator = Math.max(1, weightTotal);
    bias -= learningRate * biasGradient / denominator;
    for (const [key, gradient] of gradients) {
      const current = weights.get(key) || 0;
      weights.set(key, current - learningRate * (gradient / denominator + regularization * current));
    }
  }
  return { weights, bias, idf };
}

function getModel(examples) {
  const safeExamples = Array.isArray(examples) ? examples.slice(-500) : [];
  const key = JSON.stringify(safeExamples);
  if (key !== cachedKey) {
    cachedKey = key;
    cachedModel = train(safeExamples);
  }
  return cachedModel;
}

function classify(text, examples = []) {
  const source = String(text || '').replace(/[|\u0000-\u001f]/g, ' ').slice(0, 2000);
  const { words, counts } = features(source);
  if (!words.length) return { category: 'unknown', confidence: 0, label: 'No media metadata', evidence: 0 };
  const model = getModel(examples);
  let norm = 0;
  const values = new Map();
  for (const [key, count] of counts) {
    const idf = model.idf.get(key);
    if (!idf) continue;
    const value = (1 + Math.log(count)) * idf;
    values.set(key, value);
    norm += value * value;
  }
  if (!values.size) return { category: 'uncertain', confidence: 0.5, label: 'Unclear media', evidence: 0 };
  const scale = norm ? 1 / Math.sqrt(norm) : 1;
  let score = model.bias;
  for (const [key, value] of values) score += (model.weights.get(key) || 0) * value * scale;
  const entertainmentProbability = sigmoid(score);
  const best = entertainmentProbability >= 0.5 ? 'entertainment' : 'study';
  const confidence = Math.max(entertainmentProbability, 1 - entertainmentProbability);
  const evidence = values.size;
  const category = evidence < 2 || confidence < 0.64 ? 'uncertain' : best;
  return {
    category,
    confidence,
    label: category === 'uncertain' ? 'Unclear media' : (best === 'study' ? 'Study / productivity' : 'Likely entertainment'),
    evidence
  };
}

module.exports = { classify };
