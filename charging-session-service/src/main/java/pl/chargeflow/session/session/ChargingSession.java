package pl.chargeflow.session.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import pl.chargeflow.session.reservation.ChargingReservation;
import pl.chargeflow.session.session.exception.InvalidChargingSessionStatusTransitionException;
import pl.chargeflow.session.station.ChargingPoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "charging_session")
public class ChargingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String customerId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charging_point_id", nullable = false)
    private ChargingPoint chargingPoint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id", nullable = false)
    private ChargingReservation reservation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant stoppedAt;

    @Column(precision = 10, scale = 4)
    private BigDecimal energyConsumedKwh;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal pricePerKwh;

    @Column(precision = 10, scale = 2)
    private BigDecimal totalCost;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ChargingSession() {}

    public ChargingSession(String customerId, ChargingPoint chargingPoint,
                           ChargingReservation reservation, Instant startedAt) {
        this.customerId = customerId;
        this.chargingPoint = chargingPoint;
        this.reservation = reservation;
        this.status = SessionStatus.STARTED;
        this.startedAt = startedAt;
        this.pricePerKwh = chargingPoint.getPricePerKwh();
        this.createdAt = startedAt;
        this.updatedAt = startedAt;
    }

    public void complete(BigDecimal energyConsumedKwh) {
        if (status != SessionStatus.STARTED && status != SessionStatus.CHARGING) {
            throw new InvalidChargingSessionStatusTransitionException(id, status, SessionStatus.COMPLETED);
        }
        this.energyConsumedKwh = energyConsumedKwh;
        this.totalCost = energyConsumedKwh.multiply(pricePerKwh).setScale(2, RoundingMode.HALF_UP);
        this.status = SessionStatus.COMPLETED;
        this.stoppedAt = Instant.now();
        this.updatedAt = this.stoppedAt;
    }

    public void fail() {
        if (status != SessionStatus.STARTED && status != SessionStatus.CHARGING) {
            throw new InvalidChargingSessionStatusTransitionException(id, status, SessionStatus.FAILED);
        }
        this.status = SessionStatus.FAILED;
        this.stoppedAt = Instant.now();
        this.updatedAt = this.stoppedAt;
    }

    public boolean isActive() {
        return status == SessionStatus.STARTED || status == SessionStatus.CHARGING;
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public ChargingPoint getChargingPoint() { return chargingPoint; }
    public ChargingReservation getReservation() { return reservation; }
    public SessionStatus getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getStoppedAt() { return stoppedAt; }
    public BigDecimal getEnergyConsumedKwh() { return energyConsumedKwh; }
    public BigDecimal getPricePerKwh() { return pricePerKwh; }
    public BigDecimal getTotalCost() { return totalCost; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
