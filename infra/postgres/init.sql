-- One database per service: services never share tables, only events.
CREATE DATABASE payouts OWNER wiselite;
CREATE DATABASE fx OWNER wiselite;
CREATE DATABASE recon OWNER wiselite;
