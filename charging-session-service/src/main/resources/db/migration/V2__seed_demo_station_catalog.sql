-- Demo seed data — NOT for production use.
-- Three fictional stations in Lublin with seven charging points total.

INSERT INTO charging_station (id, name, address, city, latitude, longitude, active) VALUES
    ('11111111-0000-0000-0000-000000000001', 'Lublin Centrum',  'ul. Krakowskie Przedmieście 1', 'Lublin', 51.246454, 22.561474, true),
    ('11111111-0000-0000-0000-000000000002', 'Lublin Czechów',  'ul. Orkana 3',                  'Lublin', 51.265432, 22.528765, true),
    ('11111111-0000-0000-0000-000000000003', 'Lublin Felin',    'ul. Krężnicka 61',              'Lublin', 51.218765, 22.612345, true);

INSERT INTO charging_point (id, station_id, code, connector_type, max_power_kw, price_per_kwh, status, active) VALUES
    ('22222222-0000-0000-0000-000000000001', '11111111-0000-0000-0000-000000000001', 'LUB-C01',  'CCS',    150.00, 2.4900, 'AVAILABLE', true),
    ('22222222-0000-0000-0000-000000000002', '11111111-0000-0000-0000-000000000001', 'LUB-C02',  'CCS',    150.00, 2.4900, 'AVAILABLE', true),
    ('22222222-0000-0000-0000-000000000003', '11111111-0000-0000-0000-000000000001', 'LUB-T01',  'TYPE_2',  22.00, 1.7900, 'AVAILABLE', true),
    ('22222222-0000-0000-0000-000000000004', '11111111-0000-0000-0000-000000000002', 'LUB-CZ01', 'CCS',    100.00, 2.2900, 'AVAILABLE', true),
    ('22222222-0000-0000-0000-000000000005', '11111111-0000-0000-0000-000000000002', 'LUB-CZ02', 'TYPE_2',  22.00, 1.6900, 'AVAILABLE', true),
    ('22222222-0000-0000-0000-000000000006', '11111111-0000-0000-0000-000000000003', 'LUB-F01',  'CCS',    200.00, 2.6900, 'AVAILABLE', true),
    ('22222222-0000-0000-0000-000000000007', '11111111-0000-0000-0000-000000000003', 'LUB-F02',  'CCS',    200.00, 2.6900, 'AVAILABLE', true);
