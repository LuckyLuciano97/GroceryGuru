// Emoji icon mapping for Croatian & English grocery categories / product names
export function getCategoryIcon(item) {
  const cat = item.product?.category || '';
  const name = (item.product?.name || item.searchTerm || '').toLowerCase();
  const c = cat.toLowerCase();

  if (c.includes('voće') || c.includes('voce') || c.includes('fruit')) return '🍎';
  if (c.includes('povrće') || c.includes('povrce') || c.includes('vegetable') || c.includes('salat')) return '🥬';
  if (c.includes('meso') || c.includes('meat') || c.includes('pilet') || c.includes('svinj')) return '🥩';
  if (c.includes('riba') || c.includes('fish') || c.includes('morsk')) return '🐟';
  if (c.includes('mlijeko') || c.includes('mlije') || c.includes('dairy') || c.includes('milk')) return '🥛';
  if (c.includes('sir') || c.includes('cheese')) return '🧀';
  if (c.includes('kruh') || c.includes('pekara') || c.includes('bread')) return '🍞';
  if (c.includes('jaj') || c.includes('egg')) return '🥚';
  if (c.includes('piće') || c.includes('pice') || c.includes('sok') || c.includes('drink') || c.includes('juice') || c.includes('beverage')) return '🥤';
  if (c.includes('vino') || c.includes('pivo') || c.includes('alkohol') || c.includes('wine') || c.includes('beer')) return '🍷';
  if (c.includes('slatkiš') || c.includes('čokolad') || c.includes('cokolad') || c.includes('keks') || c.includes('snack') || c.includes('chocolate') || c.includes('cookie') || c.includes('candy')) return '🍫';
  if (c.includes('tjestenin') || c.includes('pasta') || c.includes('riža') || c.includes('riza') || c.includes('rice')) return '🍝';
  if (c.includes('začin') || c.includes('zacin') || c.includes('ulje') || c.includes('oil') || c.includes('spice')) return '🫒';
  if (c.includes('smrznut') || c.includes('frozen')) return '🧊';
  if (c.includes('higijena') || c.includes('čišćenj') || c.includes('deterdzent') || c.includes('hygiene') || c.includes('detergent')) return '🧴';
  if (c.includes('beba') || c.includes('baby') || c.includes('dječj') || c.includes('diaper')) return '👶';
  if (c.includes('konzerv') || c.includes('canned')) return '🥫';

  // Fallback: match by product name (Croatian)
  if (name.includes('mlijeko') || name.includes('jogurt') || name.includes('vrhnje')) return '🥛';
  if (name.includes('sir')) return '🧀';
  if (name.includes('kruh') || name.includes('pecivo')) return '🍞';
  if (name.includes('jaj')) return '🥚';
  if (name.includes('pilet') || name.includes('meso') || name.includes('šunk') || name.includes('salama') || name.includes('svinj') || name.includes('govedi')) return '🥩';
  if (name.includes('banana') || name.includes('jabuk') || name.includes('naranč') || name.includes('limun')) return '🍎';
  if (name.includes('rajčic') || name.includes('paprik') || name.includes('salat') || name.includes('luk') || name.includes('krumpir') || name.includes('mrkva')) return '🥬';
  if (name.includes('sok') || name.includes('voda') || name.includes('pivo') || name.includes('vino')) return '🥤';
  if (name.includes('čokolad') || name.includes('cokolad') || name.includes('keks')) return '🍫';
  if (name.includes('tjestenin') || name.includes('riža') || name.includes('brašno')) return '🍝';
  if (name.includes('ulje') || name.includes('ocat')) return '🫒';
  if (name.includes('riba') || name.includes('tuna')) return '🐟';

  // Fallback: match by product name (English)
  if (name.includes('milk') || name.includes('yogurt') || name.includes('cream')) return '🥛';
  if (name.includes('cheese')) return '🧀';
  if (name.includes('bread') || name.includes('bakery') || name.includes('bun')) return '🍞';
  if (name.includes('egg')) return '🥚';
  if (name.includes('chicken') || name.includes('meat') || name.includes('pork') || name.includes('beef') || name.includes('ham') || name.includes('sausage') || name.includes('bacon')) return '🥩';
  if (name.includes('apple') || name.includes('banana') || name.includes('orange') || name.includes('lemon') || name.includes('fruit') || name.includes('grape') || name.includes('strawberry') || name.includes('peach') || name.includes('pear')) return '🍎';
  if (name.includes('tomato') || name.includes('potato') || name.includes('onion') || name.includes('carrot') || name.includes('lettuce') || name.includes('cucumber') || name.includes('pepper') || name.includes('cabbage') || name.includes('garlic') || name.includes('vegetable')) return '🥬';
  if (name.includes('juice') || name.includes('water') || name.includes('beer') || name.includes('wine') || name.includes('soda')) return '🥤';
  if (name.includes('chocolate') || name.includes('cookie') || name.includes('candy') || name.includes('cake') || name.includes('chips')) return '🍫';
  if (name.includes('pasta') || name.includes('rice') || name.includes('flour') || name.includes('cereal')) return '🍝';
  if (name.includes('oil') || name.includes('vinegar') || name.includes('spice')) return '🫒';
  if (name.includes('fish') || name.includes('tuna') || name.includes('sardine')) return '🐟';
  if (name.includes('coffee') || name.includes('tea')) return '☕';
  if (name.includes('sugar') || name.includes('honey') || name.includes('jam')) return '🍯';
  if (name.includes('butter')) return '🧈';
  if (name.includes('frozen') || name.includes('ice cream')) return '🧊';
  if (name.includes('soap') || name.includes('shampoo') || name.includes('detergent') || name.includes('toilet paper')) return '🧴';

  return '🛒';
}
