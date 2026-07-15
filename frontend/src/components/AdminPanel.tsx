import { useState } from 'react';
import {
  useAdminConnectors,
  useConnectorTypes,
  useCreateTenant,
  useLoadSampleData,
  useRegisterConnector,
  useSetConnectorStatus,
  useStructure,
  useSyncConnector,
  useTenants,
  useTestConnector,
} from '../api/hooks';
import type { ConnectorTypeView, TestConnectionResult } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { DemoBadge } from './DemoBadge';
import { EmptyState, ErrorState, Loading } from './states';

// M1 admin panel (ADR-022): tenant onboarding, org structure, and integration management —
// connector registration with JSON-Schema-driven config forms, envelope-encrypted secrets (write-
// only), HONEST connection tests, and one-click sample data. Unauthenticated until OIDC/RBAC lands
// (DEBT-012); prod refuses to boot until then.
export function AdminPanel(): JSX.Element {
  const { tenantId } = useTenant();
  return (
    <div className="admin">
      <TenantsSection />
      {tenantId.length === 0 ? (
        <section className="card" aria-label="Tenant required">
          <EmptyState
            title="Select or create a tenant"
            hint="Pick a tenant above (Use) — integrations and structure are managed per tenant."
          />
        </section>
      ) : (
        <>
          <ConnectorsSection tenantId={tenantId} />
          <StructureSection tenantId={tenantId} />
        </>
      )}
    </div>
  );
}

function TenantsSection(): JSX.Element {
  const { tenantId, setTenantId } = useTenant();
  const tenants = useTenants();
  const create = useCreateTenant();
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');

  return (
    <section className="card" aria-label="Tenants">
      <header className="card-head">
        <h2>Tenants</h2>
      </header>
      {tenants.isLoading ? <Loading label="Loading tenants…" /> : null}
      {tenants.isError ? (
        <ErrorState error={tenants.error} onRetry={() => void tenants.refetch()} />
      ) : null}
      {tenants.data !== undefined ? (
        <ul className="admin-list">
          {tenants.data.map((t) => (
            <li key={t.id} className="admin-row">
              <div>
                <strong>{t.name}</strong> <span className="muted mono">{t.slug}</span>
                <div className="muted mono admin-id">{t.id}</div>
              </div>
              {tenantId === t.id ? (
                <span className="badge">active</span>
              ) : (
                <button type="button" className="btn" onClick={() => setTenantId(t.id)}>
                  Use
                </button>
              )}
            </li>
          ))}
        </ul>
      ) : null}
      <form
        className="admin-form"
        onSubmit={(e) => {
          e.preventDefault();
          create.mutate(
            { name, slug },
            {
              onSuccess: (t) => {
                setName('');
                setSlug('');
                setTenantId(t.id);
              },
            },
          );
        }}
      >
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="Tenant name"
          aria-label="Tenant name"
          required
        />
        <input
          value={slug}
          onChange={(e) => setSlug(e.target.value)}
          placeholder="slug (a-z0-9-)"
          aria-label="Tenant slug"
          required
        />
        <button
          type="button"
          className="btn primary"
          disabled={create.isPending}
          onClick={() => {
            create.mutate(
              { name, slug },
              {
                onSuccess: (t) => {
                  setName('');
                  setSlug('');
                  setTenantId(t.id);
                },
              },
            );
          }}
        >
          Create tenant
        </button>
      </form>
      {create.isError ? <ErrorState error={create.error} /> : null}
    </section>
  );
}

function ConnectorsSection({ tenantId }: { tenantId: string }): JSX.Element {
  const types = useConnectorTypes();
  const connectors = useAdminConnectors(tenantId);
  const test = useTestConnector(tenantId);
  const setStatus = useSetConnectorStatus(tenantId);
  const sync = useSyncConnector(tenantId);
  const sample = useLoadSampleData(tenantId);
  const syncableTypes = new Set(
    (types.data ?? []).filter((t) => t.syncAvailable).map((t) => t.type),
  );
  const [adding, setAdding] = useState<ConnectorTypeView | null>(null);
  const [testResult, setTestResult] = useState<Record<string, TestConnectionResult>>({});

  return (
    <section className="card" aria-label="Integrations">
      <header className="card-head">
        <div>
          <h2>Integrations</h2>
          <span className="card-q">Connect Jira, Bitbucket, SonarQube — or load sample data</span>
        </div>
        <button
          type="button"
          className="btn"
          disabled={sample.isPending}
          onClick={() => sample.mutate()}
        >
          {sample.isPending ? 'Computing…' : 'Load sample data & compute'}
        </button>
      </header>
      {sample.isSuccess ? (
        <p className="muted">
          Sample data loaded — {sample.data.teamsComputed} teams computed,{' '}
          {sample.data.itemsCorrelated} items correlated. See the Overview tab.{' '}
          <DemoBadge label="SIMULATION DATA" />
        </p>
      ) : null}
      {sample.isError ? <ErrorState error={sample.error} /> : null}
      {sync.isSuccess ? (
        <p className="muted">
          Sync complete — {sync.data.ingestion.emitted} records from the source,{' '}
          {sync.data.teamsComputed} teams computed. See the Overview tab.
        </p>
      ) : null}
      {sync.isError ? <ErrorState error={sync.error} /> : null}

      {types.data !== undefined && adding === null ? (
        <div className="type-grid">
          {types.data.map((t) => (
            <button
              key={t.type}
              type="button"
              className="type-card"
              onClick={() => setAdding(t)}
              aria-label={`Add ${t.displayName}`}
            >
              <strong>{t.displayName}</strong>
              <span className="muted">{t.description}</span>
              {!t.syncAvailable ? (
                <span className="badge pending">config now · sync arrives with M2</span>
              ) : (
                <span className="badge">sync available</span>
              )}
            </button>
          ))}
        </div>
      ) : null}
      {adding !== null ? (
        <ConnectorForm tenantId={tenantId} type={adding} onDone={() => setAdding(null)} />
      ) : null}

      {connectors.isLoading ? <Loading label="Loading connectors…" /> : null}
      {connectors.data !== undefined && connectors.data.length > 0 ? (
        <ul className="admin-list">
          {connectors.data.map((c) => (
            <li key={c.id} className="admin-row">
              <div>
                <strong>{c.name}</strong> <span className="muted">({c.type})</span>{' '}
                <span className={`badge ${c.status === 'DISABLED' ? 'pending' : ''}`}>
                  {c.status}
                </span>
                {c.hasSecret ? <span className="muted"> · secret ••••••••</span> : null}
                {testResult[c.id] !== undefined ? (
                  <div className={`muted test-result ${testResult[c.id].outcome.toLowerCase()}`}>
                    {testResult[c.id].outcome}: {testResult[c.id].message}
                  </div>
                ) : null}
              </div>
              <div className="admin-actions">
                {syncableTypes.has(c.type) ? (
                  <button
                    type="button"
                    className="btn primary"
                    disabled={sync.isPending}
                    onClick={() => sync.mutate(c.id)}
                  >
                    {sync.isPending ? 'Syncing…' : 'Sync now'}
                  </button>
                ) : null}
                <button
                  type="button"
                  className="btn"
                  disabled={test.isPending}
                  onClick={() =>
                    test.mutate(c.id, {
                      onSuccess: (r) => setTestResult((prev) => ({ ...prev, [c.id]: r })),
                    })
                  }
                >
                  Test
                </button>
                <button
                  type="button"
                  className="btn"
                  disabled={setStatus.isPending}
                  onClick={() =>
                    setStatus.mutate({
                      connectorId: c.id,
                      status: c.status === 'DISABLED' ? 'ACTIVE' : 'DISABLED',
                    })
                  }
                >
                  {c.status === 'DISABLED' ? 'Enable' : 'Disable'}
                </button>
              </div>
            </li>
          ))}
        </ul>
      ) : null}
      {connectors.data !== undefined && connectors.data.length === 0 ? (
        <EmptyState title="No integrations yet" hint="Pick a type above to add the first one." />
      ) : null}
    </section>
  );
}

// Renders a config form from the type's JSON Schema (string/uri properties; required enforced).
function ConnectorForm({
  tenantId,
  type,
  onDone,
}: {
  tenantId: string;
  type: ConnectorTypeView;
  onDone: () => void;
}): JSX.Element {
  const register = useRegisterConnector(tenantId);
  const schema = JSON.parse(type.configSchema) as {
    properties: Record<string, { title?: string; description?: string }>;
    required?: string[];
  };
  const fields = Object.entries(schema.properties);
  const required = new Set(schema.required ?? []);
  const [name, setName] = useState(`${type.displayName}`);
  const [config, setConfig] = useState<Record<string, string>>({});
  const [secret, setSecret] = useState('');

  return (
    <form
      className="admin-form connector-form"
      onSubmit={(e) => {
        e.preventDefault();
        register.mutate(
          {
            type: type.type,
            name,
            config,
            ...(type.secretLabel !== null ? { secret } : {}),
          },
          { onSuccess: onDone },
        );
      }}
    >
      <h3>Add {type.displayName}</h3>
      <label>
        Connector name
        <input value={name} onChange={(e) => setName(e.target.value)} required />
      </label>
      {fields.map(([key, prop]) => (
        <label key={key}>
          {prop.title ?? key}
          {required.has(key) ? ' *' : ''}
          <input
            value={config[key] ?? ''}
            onChange={(e) => setConfig((prev) => ({ ...prev, [key]: e.target.value }))}
            required={required.has(key)}
            placeholder={prop.description ?? ''}
          />
        </label>
      ))}
      {type.secretLabel !== null ? (
        <label>
          {type.secretLabel} *
          <input
            type="password"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            required
            autoComplete="new-password"
          />
          <span className="muted">Stored envelope-encrypted (AES-256-GCM); never shown again.</span>
        </label>
      ) : null}
      <div className="admin-actions">
        <button type="submit" className="btn primary" disabled={register.isPending}>
          {register.isPending ? 'Saving…' : 'Save integration'}
        </button>
        <button type="button" className="btn" onClick={onDone}>
          Cancel
        </button>
      </div>
      {register.isError ? <ErrorState error={register.error} /> : null}
    </form>
  );
}

function StructureSection({ tenantId }: { tenantId: string }): JSX.Element {
  const structure = useStructure(tenantId);
  return (
    <section className="card" aria-label="Organization structure">
      <header className="card-head">
        <h2>Organization structure</h2>
      </header>
      {structure.isLoading ? <Loading label="Loading structure…" /> : null}
      {structure.isError ? (
        <ErrorState error={structure.error} onRetry={() => void structure.refetch()} />
      ) : null}
      {structure.data !== undefined && structure.data.length === 0 ? (
        <EmptyState
          title="No structure yet"
          hint="“Load sample data & compute” creates a sample org + teams, or onboard via the API."
        />
      ) : null}
      {structure.data !== undefined && structure.data.length > 0 ? (
        <ul className="admin-tree">
          {structure.data.map((org) => (
            <li key={org.id}>
              <strong>{org.name}</strong>
              <ul>
                {org.businessUnits.map((bu) => (
                  <li key={bu.id}>
                    {bu.name}
                    <span className="muted">
                      {' '}
                      — {bu.teams.map((t) => t.name).join(', ') || 'no teams'}
                    </span>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
