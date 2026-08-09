package scenario

func DefaultPresets() []Preset {
	return []Preset{
		{ID: "russia-direct", Label: "Russia direct", Optional: true, Conditions: Conditions{Suffixes: []string{"ru", "su", "xn--p1ai", "moscow"}, GeoSites: []string{"category-gov-ru"}}, Outcome: Outcome{Mode: "direct"}},
		{ID: "okko-direct", Label: "Okko direct", Optional: true, Conditions: Conditions{Domains: []string{"okko.ru", "okko.tv", "okko.sport"}}, Outcome: Outcome{Mode: "direct"}},
		{ID: "emias-direct", Label: "EMIAS direct", Optional: true, Conditions: Conditions{Domains: []string{"emias.info"}}, Outcome: Outcome{Mode: "direct"}},
	}
}
