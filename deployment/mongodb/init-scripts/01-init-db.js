// MongoDB Initialization Script
// Runs on first container start only (when /data/db is empty)
// Executed as the root user against MONGO_INITDB_DATABASE

// Get environment variables
const dbName = process.env.MONGO_INITDB_DATABASE || 'multitenant_platform';
const appUser = process.env.MONGO_APP_USERNAME || 'platform_app';
const appPassword = process.env.MONGO_APP_PASSWORD || 'changeme_in_production';

print('=== Initializing Multitenant Platform Database ===');

// Switch to the application database
db = db.getSiblingDB(dbName);

// Create application user with readWrite permissions
db.createUser({
  user: appUser,
  pwd: appPassword,
  roles: [
    { role: 'readWrite', db: dbName }
  ]
});

print(`Created application user: ${appUser}`);

// ─────────────────────────────────────────────
// Create Collections with Schema Validation
// ─────────────────────────────────────────────

// Tenants collection
db.createCollection('tenants');
db.tenants.createIndex({ 'slug': 1 }, { unique: true });
db.tenants.createIndex({ 'domain': 1 }, { sparse: true, unique: true });
db.tenants.createIndex({ 'status': 1 });
print('Created tenants collection with indexes');

// Users collection
db.createCollection('users');
db.users.createIndex({ 'email': 1 }, { unique: true });
db.users.createIndex({ 'systemRole': 1 });
db.users.createIndex({ 'status': 1 });
print('Created users collection with indexes');

// Tenant Memberships collection
db.createCollection('tenant_memberships');
db.tenant_memberships.createIndex({ 'userId': 1, 'tenantId': 1 }, { unique: true });
db.tenant_memberships.createIndex({ 'tenantId': 1, 'status': 1 });
db.tenant_memberships.createIndex({ 'userId': 1, 'status': 1 });
print('Created tenant_memberships collection with indexes');

// Roles collection (for custom roles)
db.createCollection('roles');
db.roles.createIndex({ 'name': 1, 'tenantId': 1 }, { unique: true });
print('Created roles collection with indexes');

// Audit log collection
db.createCollection('audit_logs');
db.audit_logs.createIndex({ 'timestamp': -1 });
db.audit_logs.createIndex({ 'userId': 1, 'timestamp': -1 });
db.audit_logs.createIndex({ 'tenantId': 1, 'timestamp': -1 });
db.audit_logs.createIndex({ 'action': 1 });
print('Created audit_logs collection with indexes');

print('=== Database initialization complete ===');
