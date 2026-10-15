package generator

// Lane is one of the adversarial lanes the Gold catalogue spans. It carries the two
// things every consumer needs from a lane — how a seed becomes that lane's case
// identity, and how it becomes that lane's Gherkin.
//
// The table below is the single place that knowledge lives. Before it, the same
// "lane name -> which Generate* to call" fact was hand-written in the sweep, in the
// Gold corpus builder and in their tests, and an eighth lane meant remembering all of
// them in one commit. The committed-corpus dispatch in corpus_test.go stays out on
// purpose: it is keyed by the corpus FILENAME prefix (the apply lane's files are
// seed_NNNN.feature), an older convention that is not the lane name.
type Lane struct {
	Name    string
	CaseID  func(seed int64) CaseID
	Feature func(seed int64) string
}

// lanes is the table, in the order a seed's cases are emitted.
var lanes = []Lane{
	{"apply", func(s int64) CaseID { return Generate(s).CaseID }, func(s int64) string { return Generate(s).Feature }},
	{"run", func(s int64) CaseID { return GenerateRun(s).CaseID }, func(s int64) string { return GenerateRun(s).Feature }},
	{"rest", func(s int64) CaseID { return GenerateRest(s).CaseID }, func(s int64) string { return GenerateRest(s).Feature }},
	{"auth", func(s int64) CaseID { return GenerateAuth(s).CaseID }, func(s int64) string { return GenerateAuth(s).Feature }},
	{"entity", func(s int64) CaseID { return GenerateEntity(s).CaseID }, func(s int64) string { return GenerateEntity(s).Feature }},
	{"process", func(s int64) CaseID { return GenerateProcess(s).CaseID }, func(s int64) string { return GenerateProcess(s).Feature }},
	{"view", func(s int64) CaseID { return GenerateView(s).CaseID }, func(s int64) string { return GenerateView(s).Feature }},
	{"entityres", func(s int64) CaseID { return GenerateEntityResolution(s).CaseID }, func(s int64) string { return GenerateEntityResolution(s).Feature }},
	{"export", func(s int64) CaseID { return GenerateExport(s).CaseID }, func(s int64) string { return GenerateExport(s).Feature }},
}

// Lanes returns the lane table. Adding a lane is one entry here; every consumer —
// the witness sweep, the Gold corpus builder, the lane-count assertions — reads it.
func Lanes() []Lane { return lanes }

// LaneByName finds a lane by its name, as a CaseID's first segment spells it.
func LaneByName(name string) (Lane, bool) {
	for _, l := range lanes {
		if l.Name == name {
			return l, true
		}
	}
	return Lane{}, false
}
