// Chain logos + brand colors. Logos are the official apple-touch-icons,
// used here for a private portfolio project.
const LOGOS = {
  konzum:   require('../assets/chains/konzum.png'),
  lidl:     require('../assets/chains/lidl.png'),
  plodine:  require('../assets/chains/plodine.png'),
  kaufland: require('../assets/chains/kaufland.png'),
  spar:     require('../assets/chains/spar.png'),
  interspar: require('../assets/chains/spar.png'),
  tommy:    require('../assets/chains/tommy.png'),
  studenac: require('../assets/chains/studenac.png'),
  eurospin: require('../assets/chains/eurospin.png'),
  dm:       require('../assets/chains/dm.png'),
  ktc:      require('../assets/chains/ktc.png'),
  metro:    require('../assets/chains/metro.png'),
  ntl:      require('../assets/chains/ntl.png'),
};

// Brand colors, used for the fallback avatar and accents
const COLORS = {
  konzum: '#e3000f',
  lidl: '#0050aa',
  plodine: '#e30613',
  kaufland: '#e10915',
  spar: '#00713d',
  interspar: '#00713d',
  tommy: '#003da5',
  studenac: '#f39200',
  eurospin: '#004f9f',
  dm: '#fdc300',
  ktc: '#008c45',
  metro: '#003d7c',
  ntl: '#d71920',
};

// Display names offered in the "add card" picker.
export const KNOWN_CHAINS = [
  'Konzum', 'Lidl', 'Plodine', 'Kaufland', 'Spar', 'Tommy',
  'Studenac', 'Eurospin', 'dm', 'KTC', 'Metro', 'NTL',
];

function chainKey(chainName) {
  if (!chainName) return null;
  const n = chainName.toLowerCase();
  for (const key of Object.keys(LOGOS)) {
    if (n.includes(key)) return key;
  }
  return null;
}

export function getChainLogo(chainName) {
  const key = chainKey(chainName);
  return key ? LOGOS[key] : null;
}

export function getChainColor(chainName) {
  const key = chainKey(chainName);
  if (key && COLORS[key]) return COLORS[key];
  // deterministic fallback color from the name
  let h = 0;
  const s = (chainName || '?');
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 360;
  return `hsl(${h}, 60%, 45%)`;
}

export function getChainInitial(chainName) {
  return (chainName || '?').trim().charAt(0).toUpperCase();
}

const URLS = {
  konzum: 'https://www.konzum.hr', lidl: 'https://www.lidl.hr',
  plodine: 'https://www.plodine.hr', kaufland: 'https://www.kaufland.hr',
  spar: 'https://www.spar.hr', interspar: 'https://www.spar.hr',
  tommy: 'https://www.tommy.hr', studenac: 'https://www.studenac.hr',
  eurospin: 'https://www.eurospin.hr', dm: 'https://www.dm.hr',
  ktc: 'https://www.ktc.hr', metro: 'https://www.metro-cc.hr', ntl: 'https://www.ntl.hr',
};

export function getChainUrl(chainName) {
  const key = chainKey(chainName);
  return key && URLS[key] ? URLS[key] : null;
}
