export function createTrainingFeature(deps) {
    const { loadTrainingReportsImpl, loadTrainingReportsPageImpl } = deps;

    async function loadTrainingSetup() {
        return loadTrainingReportsImpl();
    }

    async function loadTrainingReportsPage() {
        return loadTrainingReportsPageImpl();
    }

    return { loadTrainingSetup, loadTrainingReportsPage };
}
