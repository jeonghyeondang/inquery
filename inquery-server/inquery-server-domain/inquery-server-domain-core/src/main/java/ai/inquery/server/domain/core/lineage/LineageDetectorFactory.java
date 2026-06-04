package ai.inquery.server.domain.core.lineage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Factory that selects the appropriate LineageDetector for a given database type.
 * Spring auto-discovers all LineageDetector beans and this factory matches by dbType.
 */
@Component
public class LineageDetectorFactory {

    private final List<LineageDetector> detectors;

    @Autowired
    public LineageDetectorFactory(List<LineageDetector> detectors) {
        this.detectors = detectors;
    }

    public Optional<LineageDetector> getDetector(String dbType) {
        return detectors.stream()
                .filter(d -> d.supports(dbType))
                .findFirst();
    }
}
