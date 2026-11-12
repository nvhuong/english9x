package com.english9x.analytics.aimonitor;

import com.english9x.analytics.aimonitor.AiMonitorDtos.DayCost;
import com.english9x.analytics.aimonitor.AiMonitorDtos.Monitor;
import com.english9x.analytics.aimonitor.AiMonitorDtos.PersonCost;
import com.english9x.org.api.Authz;
import com.english9x.org.api.Role;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * F56. System administrator only (the cost of the platform and the e-mail of many people): the role is checked here and again in every SQL function. Days are days of Vietnam;
 * {@code from} and {@code to} are inclusive dates (default: the last 30 days, at most 366). The alert is raised for every day whose total cost is strictly above
 * {@code e9x.grading.ai.daily-cost-alert} (VND): it only warns, grading is never blocked.
 */
@Service
class AiMonitorService {

    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_DAYS = 366;
    static final int JOB_LIMIT = 200;

    private final AiMonitorRepository repo;
    private final Authz authz;
    private final Clock clock;
    private final BigDecimal threshold;

    AiMonitorService(AiMonitorRepository repo, Authz authz, Clock clock, @Value("${e9x.grading.ai.daily-cost-alert:500000}") BigDecimal threshold) {
        this.repo = repo;
        this.authz = authz;
        this.clock = clock;
        this.threshold = threshold;
    }

    @Transactional(readOnly = true)
    public Monitor monitor(LocalDate fromDate, LocalDate toDate) {
        authz.require(Role.SYS_ADMIN, Optional.empty());
        LocalDate to = toDate != null ? toDate : clock.instant().atZone(ZONE).toLocalDate();
        LocalDate from = fromDate != null ? fromDate : to.minusDays(29);
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "from must not be after to, and the range is at most " + MAX_DAYS + " days.");
            p.setType(URI.create("urn:e9x:problem:invalid-range"));
            p.setTitle("Invalid range");
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST, p, null);
        }
        var start = from.atStartOfDay(ZONE).toInstant();
        var end = to.plusDays(1).atStartOfDay(ZONE).toInstant();
        List<PersonCost> persons = repo.cost(start, end);
        Map<LocalDate, long[]> tokens = new LinkedHashMap<>();
        Map<LocalDate, BigDecimal> cost = new LinkedHashMap<>();
        for (PersonCost c : persons) {
            cost.merge(c.day(), c.costVnd(), BigDecimal::add);
            long[] t = tokens.computeIfAbsent(c.day(), k -> new long[2]);
            t[0] += c.inputTokens() + c.outputTokens();
            t[1] += c.evaluations();
        }
        List<DayCost> days = new ArrayList<>();
        List<LocalDate> alertDays = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            BigDecimal c = cost.getOrDefault(d, BigDecimal.ZERO);
            long[] t = tokens.getOrDefault(d, new long[2]);
            boolean over = c.compareTo(threshold) > 0;
            if (over) {
                alertDays.add(d);
            }
            total = total.add(c);
            days.add(new DayCost(d, c, t[0], (int) t[1], over));
        }
        return new Monitor(from, to, threshold, !alertDays.isEmpty(), alertDays, total, days, persons, repo.failedJobs(start, end, JOB_LIMIT), repo.aiFailures(start, end),
                repo.deviation(start, end));
    }
}
