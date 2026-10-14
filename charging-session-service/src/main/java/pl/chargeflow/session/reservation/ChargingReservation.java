package pl.chargeflow.session.reservation;

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
import pl.chargeflow.session.reservation.exception.InvalidReservationStatusTransitionException;
import pl.chargeflow.session.station.ChargingPoint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "charging_reservation")
public class ChargingReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String customerId;

    @Column(nullable = false)
    private String customerEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charging_point_id", nullable = false)
    private ChargingPoint chargingPoint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(nullable = false)
    private Instant reservedAt;

    @Column(nullable = false)
    private Instant validUntil;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ChargingReservation() {}

    public ChargingReservation(String customerId, String customerEmail, ChargingPoint chargingPoint,
                                Instant reservedAt, Instant validUntil) {
        this.customerId = customerId;
        this.customerEmail = customerEmail;
        this.chargingPoint = chargingPoint;
        this.status = ReservationStatus.CREATED;
        this.reservedAt = reservedAt;
        this.validUntil = validUntil;
        this.createdAt = reservedAt;
        this.updatedAt = reservedAt;
    }

    public void cancel() {
        if (!isActive()) {
            throw new InvalidReservationStatusTransitionException(id, status, ReservationStatus.CANCELLED);
        }
        this.status = ReservationStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    public void markUsed() {
        if (!isActive()) {
            throw new InvalidReservationStatusTransitionException(id, status, ReservationStatus.USED);
        }
        this.status = ReservationStatus.USED;
        this.updatedAt = Instant.now();
    }

    public void expire() {
        if (!isActive()) {
            throw new InvalidReservationStatusTransitionException(id, status, ReservationStatus.EXPIRED);
        }
        this.status = ReservationStatus.EXPIRED;
        this.updatedAt = Instant.now();
    }

    public boolean isActive() {
        return status == ReservationStatus.CREATED || status == ReservationStatus.CONFIRMED;
    }

    public UUID getId() { return id; }
    public String getCustomerId() { return customerId; }
    public String getCustomerEmail() { return customerEmail; }
    public ChargingPoint getChargingPoint() { return chargingPoint; }
    public ReservationStatus getStatus() { return status; }
    public Instant getReservedAt() { return reservedAt; }
    public Instant getValidUntil() { return validUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
