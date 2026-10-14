package pl.chargeflow.session.station;

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
import pl.chargeflow.session.station.exception.ChargingPointNotAvailableException;
import pl.chargeflow.session.station.exception.InvalidChargingPointStatusTransitionException;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "charging_point")
public class ChargingPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", nullable = false)
    private ChargingStation station;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConnectorType connectorType;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal maxPowerKw;

    @Column(nullable = false, precision = 8, scale = 4)
    private BigDecimal pricePerKwh;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChargingPointStatus status;

    @Column(nullable = false)
    private boolean active;

    @Version
    private Long version;

    protected ChargingPoint() {}

    public ChargingPoint(ChargingStation station, String code, ConnectorType connectorType,
                         BigDecimal maxPowerKw, BigDecimal pricePerKwh,
                         ChargingPointStatus status, boolean active) {
        this.station = station;
        this.code = code;
        this.connectorType = connectorType;
        this.maxPowerKw = maxPowerKw;
        this.pricePerKwh = pricePerKwh;
        this.status = status;
        this.active = active;
    }

    public void reserve() {
        if (!isAvailableForReservation()) {
            throw new ChargingPointNotAvailableException(id);
        }
        this.status = ChargingPointStatus.RESERVED;
    }

    public void releaseReservation() {
        if (status != ChargingPointStatus.RESERVED) {
            throw new InvalidChargingPointStatusTransitionException(id, status, ChargingPointStatus.AVAILABLE);
        }
        this.status = ChargingPointStatus.AVAILABLE;
    }

    public void startCharging() {
        if (status != ChargingPointStatus.RESERVED) {
            throw new InvalidChargingPointStatusTransitionException(id, status, ChargingPointStatus.CHARGING);
        }
        this.status = ChargingPointStatus.CHARGING;
    }

    public void stopCharging() {
        if (status != ChargingPointStatus.CHARGING) {
            throw new InvalidChargingPointStatusTransitionException(id, status, ChargingPointStatus.AVAILABLE);
        }
        this.status = ChargingPointStatus.AVAILABLE;
    }

    public boolean isAvailableForReservation() {
        return active && status == ChargingPointStatus.AVAILABLE;
    }

    public void markOutOfService() {
        if (status != ChargingPointStatus.AVAILABLE) {
            throw new InvalidChargingPointStatusTransitionException(id, status, ChargingPointStatus.OUT_OF_SERVICE);
        }
        this.status = ChargingPointStatus.OUT_OF_SERVICE;
    }

    public void restoreToAvailable() {
        if (status != ChargingPointStatus.OUT_OF_SERVICE) {
            throw new InvalidChargingPointStatusTransitionException(id, status, ChargingPointStatus.AVAILABLE);
        }
        this.status = ChargingPointStatus.AVAILABLE;
    }

    public UUID getId() { return id; }
    public ChargingStation getStation() { return station; }
    public String getCode() { return code; }
    public ConnectorType getConnectorType() { return connectorType; }
    public BigDecimal getMaxPowerKw() { return maxPowerKw; }
    public BigDecimal getPricePerKwh() { return pricePerKwh; }
    public ChargingPointStatus getStatus() { return status; }
    public boolean isActive() { return active; }
}
